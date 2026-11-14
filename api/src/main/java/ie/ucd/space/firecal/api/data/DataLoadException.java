package ie.ucd.space.firecal.api.data;

/** A required data file is missing or malformed. Message always names the file. */
public class DataLoadException extends RuntimeException {
    public DataLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
