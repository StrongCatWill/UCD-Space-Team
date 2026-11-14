package ie.ucd.space.firecal.api.config;

import ie.ucd.space.firecal.api.calc.AoiQueryFactory;
import ie.ucd.space.firecal.api.geo.AoiResolver;
import ie.ucd.space.firecal.api.geo.NaBoundary;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeoConfig {

    @Bean
    public NaBoundary naBoundary(FireProperties props) {
        return NaBoundary.fromClasspath(props.naToleranceDeg());
    }

    @Bean
    public AoiResolver aoiResolver(NaBoundary na, FireProperties props) {
        return new AoiResolver(na, props.maxAreaKm2());
    }

    @Bean
    public AoiQueryFactory aoiQueryFactory(AoiResolver resolver, Clock clock, FireProperties props) {
        return new AoiQueryFactory(resolver, clock, props.baseline());
    }
}
