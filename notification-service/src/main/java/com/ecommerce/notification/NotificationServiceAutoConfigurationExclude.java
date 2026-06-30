package com.ecommerce.notification;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NotificationServiceAutoConfigurationExclude {
    // This class ensures DataSource auto-configuration is disabled
    // by being imported via @EnableAutoConfiguration with explicit exclusions
}
