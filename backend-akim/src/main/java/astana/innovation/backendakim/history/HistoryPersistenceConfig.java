package astana.innovation.backendakim.history;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration
@Profile("postgres")
public class HistoryPersistenceConfig {
    @Bean
    JdbcTransactionManager simulationTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
