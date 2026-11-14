package ie.ucd.space.firecal.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full app on the mock profile, clock fixed at 2026-11-14T08:00Z, no live SPC calls. */
@SpringBootTest(properties = {
        "fire.clock-fixed=2026-11-14T08:00:00Z",
        "fire.spc.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mock")
public abstract class ApiTestBase {
    @Autowired
    protected MockMvc mvc;
}
