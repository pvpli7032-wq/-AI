package com.pdd.pop.sdk.http.api.pop.response;

import com.pdd.pop.sdk.http.PopBaseHttpResponse;
import java.util.List;

/** Compile-only response shape; placeholder clients never return responses. */
public class PddDdkRpPromUrlGenerateResponse extends PopBaseHttpResponse {
    public RpPromotionUrlGenerateResponse getRpPromotionUrlGenerateResponse() {
        return null;
    }

    public static class RpPromotionUrlGenerateResponse {
        public List<UrlListItem> getUrlList() {
            return null;
        }

        public static class UrlListItem {
            public String getMobileUrl() {
                return null;
            }

            public String getUrl() {
                return null;
            }
        }
    }
}
