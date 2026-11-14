package ie.ucd.space.firecal.api.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/** CSV responses: text/csv, attachment name, credit + version headers, LF line endings. */
final class CsvSupport {
    private CsvSupport() {}

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setRecordSeparator('\n').setCommentMarker('#').get();

    /** Opens the response as CSV; caller writes rows. Call only after all validation passed. */
    static CSVPrinter open(HttpServletResponse resp, String filename, String version, String comment,
                           List<String> header) throws IOException {
        resp.setStatus(200);
        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        resp.setHeader("X-Harmonization-Version", version);
        resp.setHeader("X-Credit", "NASA FIRMS");
        Writer w = new OutputStreamWriter(resp.getOutputStream(), StandardCharsets.UTF_8);
        CSVPrinter p = new CSVPrinter(w, FORMAT);
        if (comment != null) p.printComment(comment);
        p.printRecord(header);
        return p;
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
