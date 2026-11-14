package ie.ucd.space.firecal.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MainTest {
    @Test
    void helpExitsZero() {
        assertEquals(0, new CommandLine(new Main()).execute("--help"));
    }
}
