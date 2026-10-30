package com.taobao.api.request;

import com.taobao.api.TaobaoRequest;
import com.taobao.api.response.TbkSpreadGetResponse;
import java.util.List;

/** Compile/test placeholder containing only properties set by TbkModule. */
public class TbkSpreadGetRequest implements TaobaoRequest<TbkSpreadGetResponse> {
    private List<TbkSpreadRequest> requests;

    public List<TbkSpreadRequest> getRequests() {
        return requests;
    }

    public void setRequests(List<TbkSpreadRequest> requests) {
        this.requests = requests;
    }

    public static class TbkSpreadRequest {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
