package ie.ucd.space.firecal.api.web;

import ie.ucd.space.firecal.api.data.DataStoreHolder;
import ie.ucd.space.firecal.api.device.DeviceService;
import ie.ucd.space.firecal.contract.api.DevicePayload;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Raspberry Pi endpoint: no envelope. */
@RestController
@Validated
public class DeviceController {

    private final DataStoreHolder holder;
    private final DeviceService devices;

    public DeviceController(DataStoreHolder holder, DeviceService devices) {
        this.holder = holder;
        this.devices = devices;
    }

    @Operation(summary = "Compact payload for the Raspberry Pi display (no envelope); ?snapshot=true serves the frozen demo payload")
    @GetMapping("/api/device/{area_id}")
    public DevicePayload device(@PathVariable("area_id") @Pattern(regexp = "[a-z0-9_]{1,40}") String areaId,
                                @RequestParam(name = "snapshot", defaultValue = "false") boolean snapshot) {
        return snapshot ? devices.snapshot(holder.current()) : devices.device(areaId, holder.current());
    }
}
