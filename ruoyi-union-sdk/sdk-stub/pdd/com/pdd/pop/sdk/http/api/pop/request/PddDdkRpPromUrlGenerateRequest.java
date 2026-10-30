package com.pdd.pop.sdk.http.api.pop.request;

import com.pdd.pop.sdk.http.PopBaseHttpRequest;
import com.pdd.pop.sdk.http.api.pop.response.PddDdkRpPromUrlGenerateResponse;
import java.util.List;

/** Compile-only request shape used by the agent authorization service. */
public class PddDdkRpPromUrlGenerateRequest
        extends PopBaseHttpRequest<PddDdkRpPromUrlGenerateResponse> {
    public void setChannelType(Integer channelType) {
        params.put("channel_type", channelType);
    }

    public void setCustomParameters(String customParameters) {
        params.put("custom_parameters", customParameters);
    }

    public void setPIdList(List<String> pIdList) {
        params.put("p_id_list", pIdList);
    }
}
