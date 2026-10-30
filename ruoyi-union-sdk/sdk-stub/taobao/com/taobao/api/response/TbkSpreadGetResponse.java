package com.taobao.api.response;

import com.taobao.api.TaobaoResponse;
import java.util.List;

/** Compile/test placeholder; results are populated only by test fixtures. */
public class TbkSpreadGetResponse extends TaobaoResponse {
    private List<?> results;

    public List<?> getResults() {
        return results;
    }

    public void setResults(List<?> results) {
        this.results = results;
    }
}
