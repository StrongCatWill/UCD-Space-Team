package org.spaceapps.fire.config;

import org.spaceapps.fire.calendar.CalendarService;
import org.spaceapps.fire.calendar.OutlookService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/** Loads the Colab artifacts once at startup (Boot 4 auto-configures a Jackson 3 JsonMapper). */
@Configuration
public class DataConfig {

    @Bean
    CalendarService calendarService(FireProperties props, ResourceLoader loader, JsonMapper jsonMapper) {
        return CalendarService.load(loader.getResource(props.data().calendar()), jsonMapper);
    }

    @Bean
    OutlookService outlookService(CalendarService calendarService) {
        return new OutlookService(calendarService);
    }
}
