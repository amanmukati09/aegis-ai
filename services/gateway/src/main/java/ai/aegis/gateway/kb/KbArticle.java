package ai.aegis.gateway.kb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Knowledge-base article, auto-generated from a resolved incident (or authored manually). */
@Entity
@Table(name = "kb_articles")
public class KbArticle {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "source_incident_id")
    private UUID sourceIncidentId;

    private String title;
    private String category;

    @Column(columnDefinition = "text")
    private String tags; // comma-separated

    @Column(columnDefinition = "text")
    private String symptoms;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @Column(columnDefinition = "text")
    private String solution;

    @Column(columnDefinition = "text")
    private String prevention;

    @Column(nullable = false)
    private String difficulty = "Intermediate";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected KbArticle() {
    }

    public KbArticle(UUID id, UUID orgId, UUID sourceIncidentId, UUID createdBy) {
        this.id = id;
        this.orgId = orgId;
        this.sourceIncidentId = sourceIncidentId;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSourceIncidentId() {
        return sourceIncidentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getSymptoms() {
        return symptoms;
    }

    public void setSymptoms(String symptoms) {
        this.symptoms = symptoms;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getSolution() {
        return solution;
    }

    public void setSolution(String solution) {
        this.solution = solution;
    }

    public String getPrevention() {
        return prevention;
    }

    public void setPrevention(String prevention) {
        this.prevention = prevention;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
