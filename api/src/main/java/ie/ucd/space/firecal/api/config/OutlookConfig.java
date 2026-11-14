package ie.ucd.space.firecal.api.config;

import ie.ucd.space.firecal.api.calc.AoiQueryFactory;
import ie.ucd.space.firecal.api.calc.CalendarService;
import ie.ucd.space.firecal.api.device.DeviceService;
import ie.ucd.space.firecal.api.outlook.ForecastService;
import ie.ucd.space.firecal.api.outlook.OutlookCache;
import ie.ucd.space.firecal.api.outlook.SpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OutlookConfig {

    @Bean
    public SpcClient spcClient(RestClient.Builder builder, FireProperties props) {
        return new SpcClient(builder.clone(), props.spc().baseUrl(), props.spc().layers());
    }

    @Bean
    public OutlookCache outlookCache(SpcClient client, FireProperties props) {
        return new OutlookCache(client, props.spc().enabled());
    }

    @Bean
    public ForecastService forecastService(OutlookCache cache) {
        return new ForecastService(cache);
    }

    @Bean
    public DeviceService deviceService(AoiQueryFactory queries, CalendarService calendar, ForecastService forecasts,
                                       FireProperties props) {
        return new DeviceService(queries, calendar, forecasts, props.alert().anomalyPct(), props.alert().officialLevel());
    }
}
