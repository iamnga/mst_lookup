package vn.ocb.mst_lookup.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.ocb.mst_lookup.service.LookupService;

import java.util.Map;

@RestController
@RequestMapping("/api/lookup")
@RequiredArgsConstructor
public class LookupController {

    private final LookupService lookupService;

    @PostMapping
    public ResponseEntity<?> lookupMST(@RequestBody Map<String, String> payload) {
        String mst = payload.get("mst");
        if (mst == null || mst.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "MST is required"));
        }
        try {
            Map<String, Object> result = lookupService.lookupMST(mst);
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", ex.getMessage()));
        }
    }
}
