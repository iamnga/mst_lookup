package vn.ocb.mst_lookup.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class LookupService {

    @Value("${twocaptcha.api-key}")
    private String twoCaptchaApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> lookupMST(String mst) throws Exception {
        // Bước 1: Lấy captcha & cookie
        String captchaUrl = "https://tracuunnt.gdt.gov.vn/tcnnt/captcha.png";
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0");
        headers.set("Referer", "https://tracuunnt.gdt.gov.vn/tcnnt/mstdn.jsp");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<byte[]> captchaResponse = restTemplate.exchange(
                captchaUrl, HttpMethod.GET, entity, byte[].class);

        byte[] captchaBytes = captchaResponse.getBody();
        List<String> setCookies = captchaResponse.getHeaders().get("Set-Cookie");
        String cookies = setCookies.stream()
                .map(c -> c.split(";")[0])
                .reduce((a, b) -> a + "; " + b)
                .orElse("");

        // Bước 2: Giải captcha với 2Captcha
        String base64Captcha = Base64.getEncoder().encodeToString(captchaBytes);
        String solvedCaptcha = solveCaptchaWith2Captcha(base64Captcha);

        // Bước 3: Gửi tra cứu MST
        String searchUrl = "https://tracuunnt.gdt.gov.vn/tcnnt/mstdn.jsp";
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("action", "action");
        params.add("id", "");
        params.add("page", "1");
        params.add("mst", mst);
        params.add("fullname", "");
        params.add("address", "");
        params.add("cmt", "");
        params.add("captcha", solvedCaptcha);

        HttpHeaders postHeaders = new HttpHeaders();
        postHeaders.set("User-Agent", "Mozilla/5.0");
        postHeaders.set("Referer", "https://tracuunnt.gdt.gov.vn/tcnnt/mstdn.jsp");
        postHeaders.set("Cookie", cookies);
        postHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> searchEntity = new HttpEntity<>(params, postHeaders);

        ResponseEntity<String> result = restTemplate.postForEntity(
                searchUrl, searchEntity, String.class);

        // Bước 4: Parse kết quả HTML
        return parseResult(result.getBody());
    }

    private String solveCaptchaWith2Captcha(String base64) throws Exception {
        // Gửi captcha lên 2captcha
        String inUrl = "http://2captcha.com/in.php";
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("key", twoCaptchaApiKey);
        body.add("method", "base64");
        body.add("body", base64);
        body.add("json", "1");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(inUrl, req, Map.class);
        Map respBody = response.getBody();
        if (respBody == null || !"1".equals(respBody.get("status").toString()))
            throw new RuntimeException("2Captcha: " + respBody);

        String captchaId = respBody.get("request").toString();

        // Poll kết quả
        for (int i = 0; i < 20; i++) {
            Thread.sleep(3000);
            Map poll = restTemplate.getForObject(
                    "http://2captcha.com/res.php?key=" + twoCaptchaApiKey +
                            "&action=get&id=" + captchaId + "&json=1",
                    Map.class
            );
            if (poll != null && "1".equals(poll.get("status").toString())) {
                return poll.get("request").toString();
            }
        }
        throw new RuntimeException("Timeout solving captcha");
    }

    private Map<String, Object> parseResult(String html) {
        Map<String, Object> result = new HashMap<>();
        Document doc = Jsoup.parse(html);

        // Tìm table class "ta_border"
        Element table = doc.selectFirst("table.ta_border");
        if (table == null) {
            result.put("error", "Không tìm thấy bảng kết quả");
            return result;
        }

        // Lấy tất cả các dòng (tr)
        Elements rows = table.select("tr");

        // Kiểm tra xem có ít nhất 2 dòng không (dòng 0 là header, dòng 1 là kết quả đầu tiên)
        if (rows.size() < 2) {
            result.put("error", "Không có dữ liệu");
            return result;
        }

        Element row = rows.get(1); // Dòng kết quả đầu tiên (sau header)
        Elements cells = row.select("td");

        if (cells.size() < 7) {
            result.put("error", "Dòng dữ liệu không đủ cột");
            return result;
        }

        // Xử lý từng cột
        String stt = cells.get(0).text().trim();
        String mst = cells.get(1).text().trim();
        String ten = cells.get(2).text().trim();
        String coQuanThue = cells.get(3).text().trim();
        String soCMT = cells.get(4).text().trim();
        String ngayThayDoi = cells.get(5).text().trim();
        String trangThai = cells.get(6).text().trim();

        // Lấy địa chỉ trụ sở nếu có (từ title trong thẻ a của cột tên)
        String diaChi = "";
        Element nameLink = cells.get(2).selectFirst("a[title]");
        if (nameLink != null && nameLink.hasAttr("title")) {
            String t = nameLink.attr("title");
            if (t.startsWith("Địa chỉ trụ sở: ")) {
                diaChi = t.replace("Địa chỉ trụ sở: ", "");
            } else {
                diaChi = t;
            }
        }

        // Đóng gói kết quả
        Map<String, Object> info = new HashMap<>();
        info.put("stt", stt);
        info.put("mst", mst);
        info.put("ten", ten);
        info.put("coQuanThue", coQuanThue);
        info.put("soCMT", soCMT);
        info.put("ngayThayDoi", ngayThayDoi);
        info.put("trangThai", trangThai);
        info.put("diaChi", diaChi);

        return info;
    }

}
