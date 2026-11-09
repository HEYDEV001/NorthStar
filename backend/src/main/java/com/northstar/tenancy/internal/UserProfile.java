package com.northstar.tenancy.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;

/**
 * A Supabase Auth user's profile (northstar.user_profiles, data-model.md section 3). Platform-wide: RLS lets app_user
 * read its own profile and its tenant's members, and write only its own. Only full_name, phone and avatar_path are
 * writable by app_user, matching the column grants; is_platform_admin is never writable through the app.
 */
@Entity
@Table(schema = "northstar", name = "user_profiles")
@DynamicInsert
@DynamicUpdate
public class UserProfile {

    @Id
    @Column(updatable = false)
    private UUID id;

    private String fullName;
    private String phone;
    private String avatarPath;

    @Column(insertable = false, updatable = false)
    private Boolean isPlatformAdmin;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant deletedAt;

    protected UserProfile() {}

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    /** The signed-in user's own name and phone; null clears a field. */
    void update(String fullName, String phone) {
        this.fullName = fullName;
        this.phone = phone;
    }
}
