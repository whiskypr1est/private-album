package com.privatealbum.app.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/** 相册（服务器上的虚拟集合，不复制文件）。支持子相册：parentId 为空表示顶层。 */
public class Album implements Serializable {
    @SerializedName("id") public long id;
    @SerializedName("name") public String name;
    @SerializedName("description") public String description;
    @SerializedName("cover_asset") public Long coverAsset;
    @SerializedName("cover_url") public String coverUrl;
    /** 直接包含的照片数（不含子相册） */
    @SerializedName("count") public int count;
    /** 含所有后代子相册的合计张数 */
    @SerializedName("total_count") public int totalCount;
    /** 直接子相册个数 */
    @SerializedName("child_count") public int childCount;
    /** 父相册 id；null 表示顶层相册 */
    @SerializedName("parent_id") public Long parentId;
    @SerializedName("created_at") public String createdAt;
    @SerializedName("updated_at") public String updatedAt;

    public boolean isTopLevel() {
        return parentId == null;
    }

    public boolean hasChildren() {
        return childCount > 0;
    }

    /** 列表里那一行小字，例如「105 张」或「3 个子相册 · 209 张」。 */
    public String subtitle() {
        if (childCount > 0) {
            return childCount + " 个子相册 · " + totalCount + " 张";
        }
        return count + " 张";
    }
}
