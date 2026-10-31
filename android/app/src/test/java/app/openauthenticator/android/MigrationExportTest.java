package app.openauthenticator.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class MigrationExportTest {
    private JSONObject account(int index, int period, String name) throws Exception {
        return new JSONObject().put("issuer", "GitHub").put("name", name + index)
                .put("secret", "JBSWY3DPEHPK3PXP").put("algorithm", "SHA1")
                .put("digits", 6).put("period", period);
    }

    @Test public void exportsGoogleBatchMetadataAndTenAccountsPerPage() throws Exception {
        JSONArray accounts = new JSONArray();
        for (int i = 0; i < 12; i++) accounts.put(account(i, 30, "user"));
        OtpUri.MigrationExport result = OtpUri.migrationExport(accounts);
        assertEquals(0, result.excluded);
        assertEquals(2, result.pages.size());
        assertEquals(10, result.pages.get(0).count);
        assertEquals(2, result.pages.get(1).count);

        long batchId = -1;
        for (int page = 0; page < result.pages.size(); page++) {
            String uri = result.pages.get(page).uri;
            assertTrue(uri.startsWith("otpauth-migration://offline?data="));
            String data = URLDecoder.decode(uri.substring(uri.indexOf("data=") + 5), StandardCharsets.UTF_8.name());
            CodedInputStream input = CodedInputStream.newInstance(Base64.getDecoder().decode(data));
            int count = 0, version = -1, size = -1, index = -1;
            long id = -1;
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    CodedInputStream otp = CodedInputStream.newInstance(input.readByteArray());
                    assertEquals(10, otp.readTag());
                    assertEquals(10, otp.readByteArray().length);
                    count++;
                } else if (field == 2) version = input.readInt32();
                else if (field == 3) size = input.readInt32();
                else if (field == 4) index = input.readInt32();
                else if (field == 5) id = input.readInt64();
                else input.skipField(tag);
            }
            assertEquals(result.pages.get(page).count, count);
            assertEquals(1, version);
            assertEquals(2, size);
            assertEquals(page, index);
            if (batchId == -1) batchId = id;
            else assertEquals(batchId, id);
        }
    }

    @Test public void shrinksPagesToFitQrCapacityAndSkipsCustomPeriods() throws Exception {
        JSONArray accounts = new JSONArray();
        String longName = "account".repeat(80);
        for (int i = 0; i < 11; i++) accounts.put(account(i, 30, longName));
        accounts.put(account(99, 45, "custom"));
        OtpUri.MigrationExport result = OtpUri.migrationExport(accounts);
        assertEquals(1, result.excluded);
        assertTrue(result.pages.size() > 2);
        int total = 0;
        for (OtpUri.MigrationPage page : result.pages) {
            assertTrue(page.count <= 10);
            total += page.count;
        }
        assertEquals(11, total);
    }
}
