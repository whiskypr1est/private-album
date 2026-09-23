package com.privatealbum.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.privatealbum.app.AlbumApp;
import com.privatealbum.app.R;
import com.privatealbum.app.model.Album;
import com.privatealbum.app.model.Responses;
import com.privatealbum.app.net.ApiException;

import java.util.ArrayList;
import java.util.List;

/** 相册列表页。 */
public class AlbumsFragment extends BaseFragment {

    /** 「全部照片」这一行的伪 id（不是真实相册，点它打开全部照片网格）。 */
    public static final long ALL_PHOTOS_ID = -1L;

    private Adapter adapter;

    public static AlbumsFragment newInstance() {
        return new AlbumsFragment();
    }

    @Override
    protected int layoutId() {
        return R.layout.fragment_grid;
    }

    @Override
    protected void setupRecycler() {
        com.privatealbum.app.util.Trace.log("AlbumsFragment.setupRecycler 开始");
        adapter = new Adapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        recyclerView.setItemAnimator(null);
        com.privatealbum.app.util.Trace.log("AlbumsFragment.setupRecycler 完成");
    }

    @Override
    protected List<Object> fetch(String cursor) throws ApiException {
        com.privatealbum.app.util.Trace.log("AlbumsFragment.fetch 请求相册列表");
        Responses.AlbumList result = api.albums();
        List<Album> albums = result.albums == null ? new ArrayList<>() : result.albums;
        // 第一行放一个「全部照片」入口：这样没加入任何相册的照片也能看到，
        // 又不用把「照片」页签加回来。
        long total = 0;
        try {
            Responses.Stats stats = api.stats();
            total = stats.total;
        } catch (ApiException e) {
            com.privatealbum.app.util.Trace.log("读取统计失败（不影响相册列表）: " + e.getMessage());
        }
        Album all = new Album();
        all.id = ALL_PHOTOS_ID;
        all.name = getString(R.string.all_photos);
        all.description = getString(R.string.all_photos_hint);
        all.count = (int) Math.max(0, total);
        List<Object> out = new ArrayList<>();
        out.add(all);
        out.addAll(albums);
        com.privatealbum.app.util.Trace.log("AlbumsFragment.fetch 拿到 " + albums.size()
                + " 个相册，全部照片 " + total + " 项");
        return out;
    }

    @Override
    protected void onBindRows() {
        com.privatealbum.app.util.Trace.log("AlbumsFragment.onBindRows 行数=" + rows.size()
                + " adapter=" + (adapter != null));
        if (adapter == null) {
            com.privatealbum.app.util.Trace.log("AlbumsFragment.onBindRows: adapter 为 null，跳过（视图已销毁）");
            return;
        }
        com.privatealbum.app.util.Guard.count("AlbumsFragment.onBindRows");
        com.privatealbum.app.util.Guard.enter("AlbumsFragment.notifyDataSetChanged");
        try {
            adapter.notifyDataSetChanged();
        } finally {
            com.privatealbum.app.util.Guard.exit();
        }
        setEmptyText(getText(R.string.empty_albums));
        updateEmptyState();
        com.privatealbum.app.util.Trace.log("AlbumsFragment.onBindRows 完成");
    }

    /** 新建相册（由 MainActivity 的 FAB 触发）。 */
    public void createAlbum() {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_album_name, null);
        com.google.android.material.textfield.TextInputEditText name = view.findViewById(R.id.name);
        com.google.android.material.textfield.TextInputEditText desc = view.findViewById(R.id.description);
        new AlertDialog.Builder(requireContext())
                .setTitle(getText(R.string.album_new))
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_save), (dlg, idx) -> {
                    String albumName = name.getText() == null ? "" : name.getText().toString().trim();
                    String description = desc.getText() == null ? "" : desc.getText().toString().trim();
                    if (albumName.isEmpty()) {
                        android.widget.Toast.makeText(requireContext(), R.string.enter_album_name,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createAlbumOnServer(albumName, description);
                })
                .show();
    }

    private void createAlbumOnServer(String albumName, String description) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    api.createAlbum(albumName, description);
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } catch (ApiException e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(requireContext(), e.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private class Adapter extends RecyclerView.Adapter<Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_album, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            try {
                bind(holder, position);
            } catch (Throwable t) {
                com.privatealbum.app.util.Trace.log("AlbumsFragment.onBindViewHolder 失败 position="
                        + position + " : " + t);
                throw t;
            }
        }

        private void bind(@NonNull Holder holder, int position) {
            Album album = (Album) rows.get(position);
            holder.name.setText(album.name);
            StringBuilder subtitle = new StringBuilder();
            subtitle.append(album.count).append(" 项");
            if (album.description != null && !album.description.isEmpty()) {
                subtitle.append(" · ").append(album.description);
            }
            holder.subtitle.setText(subtitle.toString());

            String cover = api.albumCoverUrl(album);
            if (cover == null) {
                holder.cover.setPadding(48, 48, 48, 48);
                holder.cover.setImageResource(R.drawable.ic_folder);
            } else {
                holder.cover.setPadding(0, 0, 0, 0);
                Glide.with(holder.cover.getContext())
                        .load(cover)
                        .apply(AlbumApp.thumbOptions())
                        .into(holder.cover);
            }

            holder.itemView.setOnClickListener(v -> {
                if (album.id == ALL_PHOTOS_ID) {
                    // 「全部照片」：在本页打开全部照片网格（可返回）
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).showAllPhotos();
                    }
                    return;
                }
                startActivity(AlbumDetailActivity.intent(requireContext(), album));
            });

            // 每个相册单独的「上传」按钮：上传后自动归入这个相册
            // （「全部照片」这一行的上传 = 只进总库、不进任何相册）
            holder.upload.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).showUploadChoice(
                            album.id == ALL_PHOTOS_ID ? null : album.id);
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                if (album.id == ALL_PHOTOS_ID) {
                    return true;   // 伪相册没有重命名/删除
                }
                showAlbumMenu(album);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private void showAlbumMenu(Album album) {
        String[] items = {"重命名", "设为封面（用第一张）", "删除相册"};
        new AlertDialog.Builder(requireContext())
                .setTitle(album.name)
                .setItems(items, (dlg, idx) -> {
                    if (idx == 0) {
                        renameAlbum(album);
                    } else if (idx == 1) {
                        setFirstAsCover(album);
                    } else {
                        confirmDeleteAlbum(album);
                    }
                })
                .show();
    }

    private void confirmDeleteAlbum(Album album) {
        new AlertDialog.Builder(requireContext())
                .setMessage(getText(R.string.album_delete_confirm))
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_delete), (dlgBtn, idxBtn) -> deleteAlbumOnServer(album))
                .show();
    }

    private void deleteAlbumOnServer(Album album) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    api.deleteAlbum(album.id);
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } catch (ApiException e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(requireContext(), e.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void renameAlbum(Album album) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_album_name, null);
        com.google.android.material.textfield.TextInputEditText name = view.findViewById(R.id.name);
        com.google.android.material.textfield.TextInputEditText desc = view.findViewById(R.id.description);
        name.setText(album.name);
        desc.setText(album.description);
        new AlertDialog.Builder(requireContext())
                .setTitle(getText(R.string.album_rename))
                .setView(view)
                .setNegativeButton(getText(R.string.action_cancel), null)
                .setPositiveButton(getText(R.string.action_save), (dlgRen, idxRen) -> {
                    String newName = name.getText() == null ? "" : name.getText().toString().trim();
                    String newDesc = desc.getText() == null ? "" : desc.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    renameAlbumOnServer(album, newName, newDesc);
                })
                .show();
    }

    private void renameAlbumOnServer(Album album, String newName, String newDesc) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    api.renameAlbum(album.id, newName, newDesc);
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } catch (ApiException e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(requireContext(), e.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void setFirstAsCover(Album album) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Responses.AlbumDetail detail = api.albumDetail(album.id, null);
                    if (detail.items == null || detail.items.isEmpty()) return;
                    api.setAlbumCover(album.id, detail.items.get(0).id);
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } catch (ApiException ignored) {
                    // 相册为空时不需要提示
                }
            }
        }).start();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView name;
        final TextView subtitle;
        final android.widget.Button upload;

        Holder(View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.cover);
            name = itemView.findViewById(R.id.name);
            subtitle = itemView.findViewById(R.id.subtitle);
            upload = itemView.findViewById(R.id.btnUpload);
        }
    }
}
