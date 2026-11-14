package ie.ucd.spaceteam.firecal.contract.data;

import ie.ucd.spaceteam.firecal.contract.api.CalendarData;
import ie.ucd.spaceteam.firecal.contract.api.DevicePayload;
import ie.ucd.spaceteam.firecal.contract.api.SummaryData;

/** out/demo/{area_id}.json: precomputed API payloads (the "data" parts) for one demo area. */
public record DemoFile(CalendarData calendar, SummaryData summary, DevicePayload device) {}
