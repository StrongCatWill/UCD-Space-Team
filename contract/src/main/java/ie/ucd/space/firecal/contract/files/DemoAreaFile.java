package ie.ucd.space.firecal.contract.files;

import ie.ucd.space.firecal.contract.api.CalendarData;
import ie.ucd.space.firecal.contract.api.DevicePayload;
import ie.ucd.space.firecal.contract.api.SummaryData;

/** out/demo/{area_id}.json: precomputed API data objects for one demo area (default request parameters). */
public record DemoAreaFile(String areaId, CalendarData calendar, SummaryData summary, DevicePayload device) {}
