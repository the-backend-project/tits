package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.database.Parameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// The entity having an id, found by the id event of its id entity
public class EntityByIdEvent {

  public record Entity(EntityModel model, EntityId id) {}

  private final DataSource dataSource;
  private final Map<UUID, EntityModel> entityModels;
  private final String sql;

  public EntityByIdEvent(DataSource dataSource, List<EntityModel> entityModels, String schemaName) {
    this.dataSource = dataSource;
    this.entityModels = entityModels.stream().collect(toMap(EntityModel::id, identity()));
    this.sql =
        """
        SELECT TOP(1) e.EntityId, e.EntityModelId
        FROM [{schema}].[IdEvent] ie WITH (INDEX(ixType_Data))
          INNER JOIN [{schema}].[Entity] e ON e.EntityId = ie.EntityId
        WHERE ie.Type=:type AND ie.Data=:data
        """.replace("{schema}", schemaName);
  }

  /// Completes empty if no entity has the id
  public Mono<Entity> execute(UUID type, byte[] id) {
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(
              sql,
              Map.of(
                  "type", new Parameter<>(UUID.class, type),
                  "data", new Parameter<>(byte[].class, id)
              ),
              connection
          );
          var rs = statement.executeQuery()
      ) {
        if (!rs.next())
          return null;
        UUID modelId = rs.getObject("EntityModelId", UUID.class);
        EntityModel model = entityModels.get(modelId);
        if (model == null)
          throw new IllegalStateException("Unknown entity model " + modelId + " of entity having id of type " + type);
        return new Entity(model, new EntityId.UUID(rs.getObject("EntityId", UUID.class)));
      }
    });
  }

}
