package com.example.comptable.agent.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Checkpointer persistant, backe par la base PostgreSQL existante de l'application (reutilise le
 * {@link JdbcTemplate}/{@code DataSource} deja configure par Spring Boot, plutot que le module
 * officiel {@code langgraph4j-postgres-saver} qui ouvre sa propre connexion independante - deux
 * pools de connexions vers la meme base pour un seul besoin n'a pas de sens ici). Necessaire car
 * une execution peut rester en pause (noeud {@code attente_validation}) plusieurs jours : le
 * {@code MemorySaver} fourni par defaut par LangGraph4j ne survivrait pas a un redemarrage.
 * <p>
 * Volontairement specifique a {@link PieceJustificativeState} (un seul graphe dans l'application
 * a ce stade) : le round-trip JSON de l'etat passe par {@link PieceJustificativeCheckpointData}
 * pour que l'imbrication {@link ExtractionPieceResult} soit correctement retypee au rechargement -
 * un {@code Map<String,Object>} deserialise "a l'aveugle" redeviendrait un {@code LinkedHashMap}
 * generique et casserait {@link PieceJustificativeState#extraction()}.
 */
@Component
public class JdbcCheckpointSaver extends AbstractCheckpointSaver {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcCheckpointSaver(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<Checkpoint> rowMapper = (rs, rowNum) -> Checkpoint.builder()
            .id(rs.getString("checkpoint_id"))
            .nodeId(rs.getString("node_id"))
            .nextNodeId(rs.getString("next_node_id"))
            .state(deserialiserEtat(rs.getString("state")))
            .build();

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        List<Checkpoint> lignes = jdbcTemplate.query(
                "SELECT checkpoint_id, node_id, next_node_id, state FROM agent_checkpoint "
                        + "WHERE thread_id = ? ORDER BY created_at DESC",
                rowMapper, threadId(config));
        return new LinkedList<>(lignes);
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        jdbcTemplate.update(
                "INSERT INTO agent_checkpoint (thread_id, checkpoint_id, node_id, next_node_id, state) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb)",
                threadId(config), checkpoint.getId(), checkpoint.getNodeId(), checkpoint.getNextNodeId(),
                serialiserEtat(checkpoint.getState()));
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        jdbcTemplate.update(
                "UPDATE agent_checkpoint SET node_id = ?, next_node_id = ?, state = ?::jsonb "
                        + "WHERE thread_id = ? AND checkpoint_id = ?",
                checkpoint.getNodeId(), checkpoint.getNextNodeId(), serialiserEtat(checkpoint.getState()),
                threadId(config), checkpoint.getId());
    }

    @Override
    protected BaseCheckpointSaver.Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints) {
        String thread = threadId(config);
        jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE thread_id = ?", thread);
        return new BaseCheckpointSaver.Tag(thread, checkpoints);
    }

    private String serialiserEtat(Map<String, Object> state) {
        try {
            PieceJustificativeCheckpointData data = PieceJustificativeCheckpointData.depuisMap(state);
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de serialiser l'etat du checkpoint", e);
        }
    }

    private Map<String, Object> deserialiserEtat(String json) {
        try {
            PieceJustificativeCheckpointData data = objectMapper.readValue(json, PieceJustificativeCheckpointData.class);
            return data.versMap();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de deserialiser l'etat du checkpoint", e);
        }
    }
}
