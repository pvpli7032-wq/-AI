package com.taobao.api.request;

import com.taobao.api.TaobaoRequest;
import com.taobao.api.response.TbkTpwdCreateResponse;

/** Compile/test placeholder containing only properties set by TbkModule. */
public class TbkTpwdCreateRequest implements TaobaoRequest<TbkTpwdCreateResponse> {
    private String text;
    private String url;
    private String logo;
    private String ext;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
    }
}
