package reactors.ICD_Reactors;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import prerna.util.AssetUtility;
import prerna.util.Utility;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * GetDataSubjectAreaNamesReactor reads the Data Subject Area Definitions .docx
 * from assets and returns the list of DSA names for use in frontend dropdowns.
 *
 * The document is expected to contain a table where the left column holds DSA names,
 * with the first row being a header (skipped).
 *
 * No inputs required.
 *
 * Response payload:
 * - dsaNames: list of DSA name strings
 * - count: number of names found
 */
public class GetDataSubjectAreaNamesReactor extends AbstractProjectReactor {

    private static final String DSA_RELATIVE_PATH = "/java/src/files/Data Subject Area Definitions.docx";

    public GetDataSubjectAreaNamesReactor() {
        this.keysToGet = new String[] {};
        this.keyRequired = new int[] {};
    }

    @Override
    protected NounMetadata doExecute() {
        String assetsFolder = AssetUtility.getProjectAssetsFolder(this.projectId);
        String filePath = Utility.normalizePath(assetsFolder + DSA_RELATIVE_PATH);

        List<String> dsaNames;
        try {
            dsaNames = extractDsaNames(filePath);
        } catch (IOException e) {
            return NounMetadata.getErrorNounMessage(
                "Unable to read Data Subject Area Definitions: " + e.getMessage());
        }

        if (dsaNames.isEmpty()) {
            return NounMetadata.getErrorNounMessage(
                "No DSA names found in Data Subject Area Definitions file.");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dsaNames", dsaNames);
        response.put("count", dsaNames.size());

        return new NounMetadata(response, PixelDataType.MAP);
    }

    /*
     * Opens the .docx, finds the first table, and extracts the left-column cell
     * text from each row after the header row.
     */
    private List<String> extractDsaNames(String filePath) throws IOException {
        List<String> names = new ArrayList<>();

        try (XWPFDocument document = new XWPFDocument(new FileInputStream(filePath))) {
            List<XWPFTable> tables = document.getTables();
            if (tables == null || tables.isEmpty()) {
                throw new IOException("No tables found in Data Subject Area Definitions file.");
            }

            XWPFTable table = tables.get(0);
            List<XWPFTableRow> rows = table.getRows();
            if (rows == null || rows.size() < 2) {
                return names; // Only header or empty
            }

            // Skip row 0 (header), extract left column from remaining rows
            for (int i = 1; i < rows.size(); i++) {
                XWPFTableRow row = rows.get(i);
                if (row == null || row.getTableCells() == null || row.getTableCells().isEmpty()) {
                    continue;
                }
                XWPFTableCell firstCell = row.getTableCells().get(0);
                if (firstCell == null) continue;
                String name = firstCell.getText();
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }

        return names;
    }

    @Override
    public String getReactorDescription() {
        return "Return the list of valid Data Subject Area names from the DSA definitions file.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        return null;
    }
}
