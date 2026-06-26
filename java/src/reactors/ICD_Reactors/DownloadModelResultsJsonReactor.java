package reactors.ICD_Reactors;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * DownloadModelResultsJsonReactor takes edited model results payload JSON from the frontend
 * and returns a base64-encoded JSON file response for browser download.
 *
 * Input:
 * - payload: JSON string containing edited results and metadata
 *
 * Output:
 * - fileName
 * - mimeType
 * - fileContentBase64
 * - byteCount
 */
public class DownloadModelResultsJsonReactor extends AbstractProjectReactor {

    private static final String PAYLOAD_KEY = "payload";
    private static final String MIME_TYPE = "application/json";

    public DownloadModelResultsJsonReactor() {
        this.keysToGet = new String[] {PAYLOAD_KEY};
        this.keyRequired = new int[] {1};
    }

    @Override
    protected NounMetadata doExecute() {
        String rawPayload = this.keyValue.get(PAYLOAD_KEY);
        String payload = normalizePayload(rawPayload);

        if (payload == null || payload.isBlank()) {
            return NounMetadata.getErrorNounMessage("payload is required and must be valid JSON text.");
        }

        String documentName = extractDocumentName(payload);
        String fileName = buildFileName(documentName);

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", fileName);
        response.put("mimeType", MIME_TYPE);
        response.put("fileContentBase64", base64);
        response.put("byteCount", bytes.length);

        return new NounMetadata(response, PixelDataType.MAP);
    }

    private String normalizePayload(String rawPayload) {
        if (rawPayload == null) {
            return null;
        }

        String value = rawPayload.trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }

        value = value
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\");

        return value.trim();
    }

    private String extractDocumentName(String payload) {
        String key = "\"documentName\"";
        int keyIndex = payload.indexOf(key);
        if (keyIndex < 0) {
            return "ICD";
        }

        int colonIndex = payload.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            return "ICD";
        }

        int firstQuote = payload.indexOf('"', colonIndex + 1);
        if (firstQuote < 0) {
            return "ICD";
        }

        int secondQuote = payload.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return "ICD";
        }

        String value = payload.substring(firstQuote + 1, secondQuote).trim();
        return value.isBlank() ? "ICD" : value;
    }

    private String buildFileName(String documentName) {
        String baseName = documentName;
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }

        String safe = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "ICD";
        }

        return safe + "_model_results.json";
    }

    @Override
    public String getReactorDescription() {
        return "Prepare edited model results JSON for browser download.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        if (PAYLOAD_KEY.equals(key)) {
            return "A JSON string containing edited model result rows and metadata.";
        }
        return null;
    }
}
