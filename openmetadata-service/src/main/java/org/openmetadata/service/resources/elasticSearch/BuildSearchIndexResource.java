package org.openmetadata.service.resources.elasticSearch;

import static org.openmetadata.service.Entity.TABLE;
import static org.openmetadata.service.Entity.TEAM;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.api.CreateEventPublisherJob;
import org.openmetadata.schema.api.CreateEventPublisherJob.RunMode;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.settings.EventPublisherJob;
import org.openmetadata.schema.settings.FailureDetails;
import org.openmetadata.schema.settings.Stats;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.Entity;
import org.openmetadata.service.OpenMetadataApplicationConfig;
import org.openmetadata.service.elasticsearch.ElasticSearchIndexDefinition;
import org.openmetadata.service.elasticsearch.ElasticSearchIndexFactory;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.jdbi3.ListFilter;
import org.openmetadata.service.jdbi3.UserRepository;
import org.openmetadata.service.resources.Collection;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.util.ElasticSearchClientUtils;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.JsonUtils;
import org.openmetadata.service.util.ResultList;

@Path("/v1/indexResource")
@Api(value = "Elastic Search Collection", tags = "Elastic Search Collection")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Collection(name = "indexResource")
@Slf4j
public class BuildSearchIndexResource {
  public static final String ELASTIC_SEARCH_EXTENSION = "service.eventPublisher";
  public static final String ELASTIC_SEARCH_ENTITY_FQN_STREAM = "eventPublisher:ElasticSearch:STREAM";
  public static final String ELASTIC_SEARCH_ENTITY_FQN_BATCH = "eventPublisher:ElasticSearch:BATCH";
  private RestHighLevelClient client;
  private ElasticSearchIndexDefinition elasticSearchIndexDefinition;
  private final CollectionDAO dao;
  private final Authorizer authorizer;
  private final ExecutorService threadScheduler;
  private final UserRepository userRepository;

  public BuildSearchIndexResource(CollectionDAO dao, Authorizer authorizer) {
    this.dao = dao;
    this.userRepository = new UserRepository(dao);
    this.authorizer = authorizer;
    this.threadScheduler =
        new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(5), new ThreadPoolExecutor.CallerRunsPolicy());
  }

  public void initialize(OpenMetadataApplicationConfig config) throws IOException {
    if (config.getElasticSearchConfiguration() != null) {
      this.client = ElasticSearchClientUtils.createElasticSearchClient(config.getElasticSearchConfiguration());
      this.elasticSearchIndexDefinition = new ElasticSearchIndexDefinition(client, dao);
    }
  }

  private BulkProcessor getBulkProcessor(BulkProcessorListener listener, int bulkSize, int flushIntervalInSeconds) {
    BiConsumer<BulkRequest, ActionListener<BulkResponse>> bulkConsumer =
        (request, bulkListener) -> client.bulkAsync(request, RequestOptions.DEFAULT, bulkListener);
    BulkProcessor.Builder builder = BulkProcessor.builder(bulkConsumer, listener, "es-reindex");
    builder.setBulkActions(bulkSize);
    builder.setConcurrentRequests(2);
    builder.setFlushInterval(TimeValue.timeValueSeconds(flushIntervalInSeconds));
    builder.setBackoffPolicy(BackoffPolicy.constantBackoff(TimeValue.timeValueSeconds(1L), 3));
    return builder.build();
  }

  @POST
  @Path("/reindex")
  @Operation(
      operationId = "reindexEntities",
      summary = "Reindex Entities",
      tags = "indexResource",
      description = "Reindex Elastic Search Entities",
      responses = {
        @ApiResponse(responseCode = "200", description = "Success"),
        @ApiResponse(responseCode = "404", description = "Bot for instance {id} is not found")
      })
  public Response reindexAllEntities(
      @Context UriInfo uriInfo, @Context SecurityContext securityContext, @Valid CreateEventPublisherJob createRequest)
      throws IOException {
    // Only admins  can issue a reindex request
    authorizer.authorizeAdmin(securityContext, false);
    User user =
        userRepository.getByName(null, securityContext.getUserPrincipal().getName(), userRepository.getFields("id"));
    if (createRequest.getRunMode() == RunMode.BATCH) {
      return startReindexingBatchMode(uriInfo, user.getId(), createRequest);
    } else {
      return startReindexingStreamMode(uriInfo, user.getId(), createRequest);
    }
  }

  @GET
  @Path("/reindex/status/{runMode}")
  @Operation(
      operationId = "getReindexAllLastJobStatus",
      summary = "Get Last Run Reindex All Job Status",
      tags = "indexResource",
      description = "Reindex All job last status",
      responses = {
        @ApiResponse(responseCode = "200", description = "Success"),
        @ApiResponse(responseCode = "404", description = "Bot for instance {id} is not found")
      })
  public Response reindexAllJobLastStatus(
      @Context UriInfo uriInfo, @Context SecurityContext securityContext, @PathParam("runMode") String runMode)
      throws IOException {
    // Only admins  can issue a reindex request
    authorizer.authorizeAdmin(securityContext, false);
    // Check if there is a running job for reindex for requested entity
    String record;
    if (runMode.equals(RunMode.BATCH.toString())) {
      record =
          dao.entityExtensionTimeSeriesDao()
              .getLatestExtension(ELASTIC_SEARCH_ENTITY_FQN_BATCH, ELASTIC_SEARCH_EXTENSION);
    } else if (runMode.equals(RunMode.STREAM.toString())) {
      record =
          dao.entityExtensionTimeSeriesDao()
              .getLatestExtension(ELASTIC_SEARCH_ENTITY_FQN_STREAM, ELASTIC_SEARCH_EXTENSION);
    } else {
      return Response.status(Response.Status.BAD_REQUEST).entity("Invalid Run Mode").build();
    }
    if (record != null) {
      return Response.status(Response.Status.OK).entity(JsonUtils.readValue(record, EventPublisherJob.class)).build();
    }
    return Response.status(Response.Status.NOT_FOUND).entity("No Last Run.").build();
  }

  private synchronized Response startReindexingStreamMode(
      UriInfo uriInfo, UUID startedBy, CreateEventPublisherJob createRequest) {
    // create a new Job
    threadScheduler.submit(() -> this.submitStreamJob(uriInfo, startedBy, createRequest));
    return Response.status(Response.Status.OK).entity("Reindexing Started").build();
  }

  private synchronized Response startReindexingBatchMode(
      UriInfo uriInfo, UUID startedBy, CreateEventPublisherJob createRequest) {
    // create a new Job
    threadScheduler.submit(
        () -> {
          try {
            this.submitBatchJob(uriInfo, startedBy, createRequest);
          } catch (IOException e) {
            LOG.error("Reindexing Batch Job error", e);
          }
        });
    return Response.status(Response.Status.OK).entity("Reindexing Started").build();
  }

  private synchronized void submitStreamJob(UriInfo uriInfo, UUID startedBy, CreateEventPublisherJob createRequest) {
    try {
      for (String entityName : createRequest.getEntities()) {
        updateEntityStream(uriInfo, startedBy, entityName, createRequest);
      }

      // Mark the Job end
      String reindexJobString =
          dao.entityExtensionTimeSeriesDao()
              .getLatestExtension(ELASTIC_SEARCH_ENTITY_FQN_STREAM, ELASTIC_SEARCH_EXTENSION);
      EventPublisherJob latestJob = JsonUtils.readValue(reindexJobString, EventPublisherJob.class);
      long lastUpdateTime = latestJob.getTimestamp();
      Long time = Date.from(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()).getTime();
      latestJob.setTimestamp(time);
      latestJob.setEndTime(time);
      if (latestJob.getFailureDetails() != null) {
        latestJob.setStatus(EventPublisherJob.Status.ACTIVEWITHERROR);
      } else {
        latestJob.setStatus(EventPublisherJob.Status.ACTIVE);
      }
      dao.entityExtensionTimeSeriesDao()
          .update(
              ELASTIC_SEARCH_ENTITY_FQN_STREAM,
              ELASTIC_SEARCH_EXTENSION,
              JsonUtils.pojoToJson(latestJob),
              lastUpdateTime);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private synchronized void submitBatchJob(UriInfo uriInfo, UUID startedBy, CreateEventPublisherJob createRequest)
      throws IOException {
    long updateTime = Date.from(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()).getTime();
    String recordString =
        dao.entityExtensionTimeSeriesDao().getExtension(ELASTIC_SEARCH_ENTITY_FQN_BATCH, ELASTIC_SEARCH_EXTENSION);
    EventPublisherJob lastRecord = JsonUtils.readValue(recordString, EventPublisherJob.class);
    long originalLastUpdate = lastRecord.getTimestamp();
    lastRecord.setStatus(EventPublisherJob.Status.STARTING);
    lastRecord.setStats(new Stats().withFailed(0).withTotal(0).withSuccess(0));
    lastRecord.setTimestamp(updateTime);
    lastRecord.setEntities(createRequest.getEntities());
    dao.entityExtensionTimeSeriesDao()
        .update(
            ELASTIC_SEARCH_ENTITY_FQN_BATCH,
            ELASTIC_SEARCH_EXTENSION,
            JsonUtils.pojoToJson(lastRecord),
            originalLastUpdate);

    // Update Listener for only Batch
    BulkProcessorListener bulkProcessorListener = new BulkProcessorListener(dao, startedBy);
    BulkProcessor processor =
        getBulkProcessor(bulkProcessorListener, createRequest.getBatchSize(), createRequest.getFlushIntervalInSec());

    for (String entityName : createRequest.getEntities()) {
      try {
        updateEntityBatch(processor, bulkProcessorListener, uriInfo, entityName, createRequest);
      } catch (Exception ex) {
        LOG.error("Reindexing intermittent failure for entityType : {}", entityName, ex);
      }
    }
  }

  private synchronized void updateEntityBatch(
      BulkProcessor processor,
      BulkProcessorListener listener,
      UriInfo uriInfo,
      String entityType,
      CreateEventPublisherJob createRequest) {
    listener.allowTotalRequestUpdate();

    ElasticSearchIndexDefinition.ElasticSearchIndexType indexType =
        elasticSearchIndexDefinition.getIndexMappingByEntityType(entityType);

    if (Boolean.TRUE.equals(createRequest.getRecreateIndex())) {
      // Delete index
      elasticSearchIndexDefinition.deleteIndex(indexType);
      // Create index
      elasticSearchIndexDefinition.createIndex(indexType);
    }

    // Start fetching a list of Entities and pushing them to ES
    EntityRepository<EntityInterface> entityRepository = Entity.getEntityRepository(entityType);
    List<String> allowedFields = entityRepository.getAllowedFields();
    String fields = String.join(",", allowedFields);
    ResultList<EntityInterface> result;
    String after = null;
    try {
      do {
        if (entityType.equals(TEAM)) {
          // just name and display name are needed
          fields = "name,displayName";
        }
        result =
            entityRepository.listAfter(
                uriInfo,
                new EntityUtil.Fields(allowedFields, fields),
                new ListFilter(Include.ALL),
                createRequest.getBatchSize(),
                after);
        listener.addRequests(result.getPaging().getTotal());
        updateElasticSearchForEntityBatch(indexType, processor, entityType, result.getData());
        processor.flush();
        after = result.getPaging().getAfter();
      } while (after != null);
    } catch (Exception ex) {
      LOG.error("Failed in listing all Entities of type : {}", entityType);
    }
  }

  private synchronized void updateEntityStream(
      UriInfo uriInfo, UUID startedBy, String entityType, CreateEventPublisherJob createRequest) throws IOException {

    ElasticSearchIndexDefinition.ElasticSearchIndexType indexType =
        elasticSearchIndexDefinition.getIndexMappingByEntityType(entityType);

    if (Boolean.TRUE.equals(createRequest.getRecreateIndex())) {
      // Delete index
      elasticSearchIndexDefinition.deleteIndex(indexType);
      // Create index
      elasticSearchIndexDefinition.createIndex(indexType);
    }

    // Start fetching a list of Entities and pushing them to ES
    EntityRepository<EntityInterface> entityRepository = Entity.getEntityRepository(entityType);
    List<String> allowedFields = entityRepository.getAllowedFields();
    String fields = String.join(",", allowedFields);
    ResultList<EntityInterface> result;
    String after = null;
    try {
      do {
        result =
            entityRepository.listAfter(
                uriInfo,
                new EntityUtil.Fields(allowedFields, fields),
                new ListFilter(Include.ALL),
                createRequest.getBatchSize(),
                after);
        updateElasticSearchForEntityStream(entityType, result.getData());
        after = result.getPaging().getAfter();
      } while (after != null);
    } catch (Exception ex) {
      LOG.error("Failed in listing all Entities of type : {}", entityType);
    }
  }

  private synchronized void updateElasticSearchForEntityBatch(
      ElasticSearchIndexDefinition.ElasticSearchIndexType indexType,
      BulkProcessor bulkProcessor,
      String entityType,
      List<EntityInterface> entities) {
    for (EntityInterface entity : entities) {
      if (entityType.equals(TABLE)) {
        ((Table) entity).getColumns().forEach(table -> table.setProfile(null));
      }
      UpdateRequest request = getUpdateRequest(indexType, entityType, entity);
      if (request != null) {
        bulkProcessor.add(request);
      }
    }
  }

  private synchronized void updateElasticSearchForEntityStream(String entityType, List<EntityInterface> entities)
      throws IOException {
    String reindexJobString =
        dao.entityExtensionTimeSeriesDao()
            .getLatestExtension(ELASTIC_SEARCH_ENTITY_FQN_STREAM, ELASTIC_SEARCH_EXTENSION);
    EventPublisherJob latestJob = JsonUtils.readValue(reindexJobString, EventPublisherJob.class);
    Long lastUpdateTime = latestJob.getTimestamp();
    ElasticSearchIndexDefinition.ElasticSearchIndexType indexType =
        elasticSearchIndexDefinition.getIndexMappingByEntityType(entityType);
    for (EntityInterface entity : entities) {
      if (entityType.equals(TABLE)) {
        ((Table) entity).getColumns().forEach(table -> table.setProfile(null));
      }
      FailureDetails failureDetails;
      Long time = Date.from(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()).getTime();
      try {
        client.update(getUpdateRequest(indexType, entityType, entity), RequestOptions.DEFAULT);
      } catch (IOException ex) {
        failureDetails = new FailureDetails().withLastFailedAt(time).withLastFailedReason(ex.getMessage());
        latestJob.setFailureDetails(failureDetails);
        latestJob.setStatus(EventPublisherJob.Status.ACTIVEWITHERROR);
      }
      latestJob.setTimestamp(time);
      dao.entityExtensionTimeSeriesDao()
          .update(
              ELASTIC_SEARCH_ENTITY_FQN_STREAM,
              ELASTIC_SEARCH_EXTENSION,
              JsonUtils.pojoToJson(latestJob),
              lastUpdateTime);
      lastUpdateTime = time;
    }
  }

  private UpdateRequest getUpdateRequest(
      ElasticSearchIndexDefinition.ElasticSearchIndexType indexType, String entityType, EntityInterface entity) {
    try {
      UpdateRequest updateRequest = new UpdateRequest(indexType.indexName, entity.getId().toString());
      updateRequest.doc(
          JsonUtils.pojoToJson(
              Objects.requireNonNull(ElasticSearchIndexFactory.buildIndex(entityType, entity)).buildESDoc()),
          XContentType.JSON);
      updateRequest.docAsUpsert(true);
      return updateRequest;
    } catch (Exception ex) {
      LOG.error("Failed in creating update Request for indexType : {}, entityType: {}", indexType, entityType, ex);
    }
    return null;
  }
}
