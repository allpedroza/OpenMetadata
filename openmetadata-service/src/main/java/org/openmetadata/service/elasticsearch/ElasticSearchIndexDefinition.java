package org.openmetadata.service.elasticsearch;

import static org.openmetadata.service.resources.elasticSearch.BuildSearchIndexResource.ELASTIC_SEARCH_ENTITY_FQN_STREAM;
import static org.openmetadata.service.resources.elasticSearch.BuildSearchIndexResource.ELASTIC_SEARCH_EXTENSION;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.PutMappingRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.openmetadata.schema.settings.EventPublisherJob;
import org.openmetadata.schema.settings.FailureDetails;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
public class ElasticSearchIndexDefinition {
  private final CollectionDAO dao;
  final EnumMap<ElasticSearchIndexType, ElasticSearchIndexStatus> elasticSearchIndexes =
      new EnumMap<>(ElasticSearchIndexType.class);
  private final RestHighLevelClient client;

  public ElasticSearchIndexDefinition(RestHighLevelClient client, CollectionDAO dao) {
    this.dao = dao;
    this.client = client;
    for (ElasticSearchIndexType elasticSearchIndexType : ElasticSearchIndexType.values()) {
      elasticSearchIndexes.put(elasticSearchIndexType, ElasticSearchIndexStatus.NOT_CREATED);
    }
  }

  public enum ElasticSearchIndexStatus {
    CREATED,
    NOT_CREATED,
    FAILED
  }

  public enum ElasticSearchIndexType {
    TABLE_SEARCH_INDEX("table_search_index", "/elasticsearch/table_index_mapping.json"),
    TOPIC_SEARCH_INDEX("topic_search_index", "/elasticsearch/topic_index_mapping.json"),
    DASHBOARD_SEARCH_INDEX("dashboard_search_index", "/elasticsearch/dashboard_index_mapping.json"),
    PIPELINE_SEARCH_INDEX("pipeline_search_index", "/elasticsearch/pipeline_index_mapping.json"),
    USER_SEARCH_INDEX("user_search_index", "/elasticsearch/user_index_mapping.json"),
    TEAM_SEARCH_INDEX("team_search_index", "/elasticsearch/team_index_mapping.json"),
    GLOSSARY_SEARCH_INDEX("glossary_search_index", "/elasticsearch/glossary_index_mapping.json"),
    MLMODEL_SEARCH_INDEX("mlmodel_search_index", "/elasticsearch/mlmodel_index_mapping.json"),
    TAG_SEARCH_INDEX("tag_search_index", "/elasticsearch/tag_index_mapping.json");

    public final String indexName;
    public final String indexMappingFile;

    ElasticSearchIndexType(String indexName, String indexMappingFile) {
      this.indexName = indexName;
      this.indexMappingFile = indexMappingFile;
    }
  }

  public void createIndexes() {
    for (ElasticSearchIndexType elasticSearchIndexType : ElasticSearchIndexType.values()) {
      createIndex(elasticSearchIndexType);
    }
  }

  public void updateIndexes() {
    for (ElasticSearchIndexType elasticSearchIndexType : ElasticSearchIndexType.values()) {
      updateIndex(elasticSearchIndexType);
    }
  }

  public void dropIndexes() {
    for (ElasticSearchIndexType elasticSearchIndexType : ElasticSearchIndexType.values()) {
      deleteIndex(elasticSearchIndexType);
    }
  }

  public boolean checkIndexExistsOrCreate(ElasticSearchIndexType indexType) {
    boolean exists = elasticSearchIndexes.get(indexType) == ElasticSearchIndexStatus.CREATED;
    if (!exists) {
      exists = createIndex(indexType);
    }
    return exists;
  }

  public boolean createIndex(ElasticSearchIndexType elasticSearchIndexType) {
    try {
      GetIndexRequest gRequest = new GetIndexRequest(elasticSearchIndexType.indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      if (!exists) {
        String elasticSearchIndexMapping = getIndexMapping(elasticSearchIndexType);
        CreateIndexRequest request = new CreateIndexRequest(elasticSearchIndexType.indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        CreateIndexResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
        LOG.info("{} Created {}", elasticSearchIndexType.indexName, createIndexResponse.isAcknowledged());
      }
      setIndexStatus(elasticSearchIndexType, ElasticSearchIndexStatus.CREATED);
    } catch (Exception e) {
      setIndexStatus(elasticSearchIndexType, ElasticSearchIndexStatus.FAILED);
      updateElasticSearchFailureStatus(
          getContext("Creating Index", elasticSearchIndexType.indexName),
          String.format("Reason: [%s] , Trace : [%s]", e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to create Elastic Search indexes due to", e);
      return false;
    }
    return true;
  }

  private String getContext(String type, String info) {
    return String.format("Failed While : %s \n Additional Info:  %s ", type, info);
  }

  private void updateIndex(ElasticSearchIndexType elasticSearchIndexType) {
    try {
      GetIndexRequest gRequest = new GetIndexRequest(elasticSearchIndexType.indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      String elasticSearchIndexMapping = getIndexMapping(elasticSearchIndexType);
      if (exists) {
        PutMappingRequest request = new PutMappingRequest(elasticSearchIndexType.indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        AcknowledgedResponse putMappingResponse = client.indices().putMapping(request, RequestOptions.DEFAULT);
        LOG.info("{} Updated {}", elasticSearchIndexType.indexName, putMappingResponse.isAcknowledged());
      } else {
        CreateIndexRequest request = new CreateIndexRequest(elasticSearchIndexType.indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        CreateIndexResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
        LOG.info("{} Created {}", elasticSearchIndexType.indexName, createIndexResponse.isAcknowledged());
      }
      setIndexStatus(elasticSearchIndexType, ElasticSearchIndexStatus.CREATED);
    } catch (Exception e) {
      setIndexStatus(elasticSearchIndexType, ElasticSearchIndexStatus.FAILED);
      updateElasticSearchFailureStatus(
          getContext("Updating Index", elasticSearchIndexType.indexName),
          String.format("Reason: [%s] , Trace : [%s]", e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to update Elastic Search indexes due to", e);
    }
  }

  public void deleteIndex(ElasticSearchIndexType elasticSearchIndexType) {
    try {
      GetIndexRequest gRequest = new GetIndexRequest(elasticSearchIndexType.indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      if (exists) {
        DeleteIndexRequest request = new DeleteIndexRequest(elasticSearchIndexType.indexName);
        AcknowledgedResponse deleteIndexResponse = client.indices().delete(request, RequestOptions.DEFAULT);
        LOG.info("{} Deleted {}", elasticSearchIndexType.indexName, deleteIndexResponse.isAcknowledged());
      }
    } catch (IOException e) {
      updateElasticSearchFailureStatus(
          getContext("Deleting Index", elasticSearchIndexType.indexName),
          String.format("Reason: [%s] , Trace : [%s]", e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to delete Elastic Search indexes due to", e);
    }
  }

  private void setIndexStatus(ElasticSearchIndexType indexType, ElasticSearchIndexStatus elasticSearchIndexStatus) {
    elasticSearchIndexes.put(indexType, elasticSearchIndexStatus);
  }

  public String getIndexMapping(ElasticSearchIndexType elasticSearchIndexType) throws IOException {
    InputStream in = ElasticSearchIndexDefinition.class.getResourceAsStream(elasticSearchIndexType.indexMappingFile);
    return new String(in.readAllBytes());
  }

  public ElasticSearchIndexType getIndexMappingByEntityType(String type) {
    if (type.equalsIgnoreCase(Entity.TABLE)) {
      return ElasticSearchIndexType.TABLE_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.DASHBOARD)) {
      return ElasticSearchIndexType.DASHBOARD_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.PIPELINE)) {
      return ElasticSearchIndexType.PIPELINE_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.TOPIC)) {
      return ElasticSearchIndexType.TOPIC_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.USER)) {
      return ElasticSearchIndexType.USER_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.TEAM)) {
      return ElasticSearchIndexType.TEAM_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.GLOSSARY)) {
      return ElasticSearchIndexType.GLOSSARY_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.MLMODEL)) {
      return ElasticSearchIndexType.MLMODEL_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.GLOSSARY_TERM)) {
      return ElasticSearchIndexType.GLOSSARY_SEARCH_INDEX;
    } else if (type.equalsIgnoreCase(Entity.TAG)) {
      return ElasticSearchIndexType.TAG_SEARCH_INDEX;
    }
    throw new RuntimeException("Failed to find index doc for type " + type);
  }

  private void updateElasticSearchFailureStatus(String failedFor, String failureMessage) {
    try {
      long updateTime = Date.from(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()).getTime();
      String recordString =
          dao.entityExtensionTimeSeriesDao().getExtension(ELASTIC_SEARCH_ENTITY_FQN_STREAM, ELASTIC_SEARCH_EXTENSION);
      EventPublisherJob lastRecord = JsonUtils.readValue(recordString, EventPublisherJob.class);
      long originalLastUpdate = lastRecord.getTimestamp();
      lastRecord.setStatus(EventPublisherJob.Status.ACTIVEWITHERROR);
      lastRecord.setTimestamp(updateTime);
      lastRecord.setFailureDetails(
          new FailureDetails()
              .withContext(failedFor)
              .withLastFailedAt(updateTime)
              .withLastFailedReason(failureMessage));

      dao.entityExtensionTimeSeriesDao()
          .update(
              ELASTIC_SEARCH_ENTITY_FQN_STREAM,
              ELASTIC_SEARCH_EXTENSION,
              JsonUtils.pojoToJson(lastRecord),
              originalLastUpdate);
    } catch (Exception e) {
      LOG.error("Failed to Update Elastic Search Job Info");
    }
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Jacksonized
@Getter
@Builder
class ElasticSearchSuggest {
  String input;
  Integer weight;
}

@Getter
@Builder
class FlattenColumn {
  String name;
  String description;
  List<TagLabel> tags;
}

class ParseTags {
  TagLabel tierTag;
  final List<TagLabel> tags;

  ParseTags(List<TagLabel> tags) {
    if (!tags.isEmpty()) {
      List<TagLabel> tagsList = new ArrayList<>(tags);
      for (TagLabel tag : tagsList) {
        if (tag.getTagFQN().toLowerCase().matches("(.*)tier(.*)")) {
          tierTag = tag;
          break;
        }
      }
      if (tierTag != null) {
        tagsList.remove(tierTag);
      }
      this.tags = tagsList;
    } else {
      this.tags = tags;
    }
  }
}
