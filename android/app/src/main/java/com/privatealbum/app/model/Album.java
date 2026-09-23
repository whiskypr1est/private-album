package com.privatealbum.app.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/** 相册（服务器上的虚拟集合，不复制文件）。 */
public class Album implements Serializable {
    @SerializedName("id") public long id;
    @SerializedName("name") public String name;
    @SerializedName("description") public String description;
    @SerializedName("cover_asset") public Long coverAsset;
    @SerializedName("cover_url") public String coverUrl;
    @SerializedName("count") public int count;
    @SerializedName("created_at") public String createdAt;
    @SerializedName("updated_at") public String updatedAt;
}
