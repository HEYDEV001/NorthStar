package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.MissingTenantContextException;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

/** JPA and pool settings that keep tenant data from outliving its transaction (D7, step 1.4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JpaSafetyTests {

    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;
    private final ApplicationContext context;

    JpaSafetyTests(
            @Autowired EntityManagerFactory entityManagerFactory,
            @Autowired DataSource dataSource,
            @Autowired ApplicationContext context) {
        this.entityManagerFactory = entityManagerFactory;
        this.dataSource = dataSource;
        this.context = context;
    }

    @Test
    void secondLevelAndQueryCachesAreDisabled() {
        SessionFactoryOptions options =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();

        assertThat(options.isSecondLevelCacheEnabled()).isFalse();
        assertThat(options.isQueryCacheEnabled()).isFalse();
    }

    @Test
    void openInViewIsOff() {
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class))
                .isEmpty();
    }

    @Test
    void poolHasNoTestQueryOrInitSql() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        assertThat(pool.getConnectionTestQuery()).isNull();
        assertThat(pool.getConnectionInitSql()).isNull();
    }

    @Test
    void appDatasourceIsTheTenantGuard() {
        assertThat(dataSource).isInstanceOf(TenantGuardDataSource.class);
    }

    /**
     * Schema validation needs one statement while the app starts. The window that allows it is closed by the time the
     * context is ready, so statements fail afterwards, including on the thread that started the app (this test thread
     * started the cached context).
     */
    @Test
    void startupWindowIsClosedAndStatementsFailAfterStartup() throws SQLException {
        assertThat(((TenantGuardDataSource) dataSource).isStartupWindowOpen()).isFalse();

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(connection::createStatement).isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement("select 1"))
                    .isInstanceOf(MissingTenantContextException.class);
        }
        assertThatThrownBy(entityManagerFactory::createEntityManager).isInstanceOf(MissingTenantContextException.class);
    }
}
