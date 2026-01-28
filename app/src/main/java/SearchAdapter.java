package com.hfm.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import com.hfm.app.SearchActivity.DateHeader;
import com.hfm.app.SearchActivity.SearchResult;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final Context context;
    private List<Object> listItems; 
    private final OnItemClickListener itemClickListener;
    private final OnHeaderCheckedChangeListener headerCheckedListener;
    private final OnHeaderClickListener headerClickListener;
    
    // RESTORED: Executor for PDF/APK generation to preserve existing logic
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4);

    public interface OnItemClickListener {
        void onItemClick(SearchResult item);
        void onItemLongClick(SearchResult item);
    }

    public interface OnHeaderCheckedChangeListener {
        void onHeaderCheckedChanged(DateHeader header, boolean isChecked);
    }

    public interface OnHeaderClickListener {
        void onHeaderClick(DateHeader header);
    }

    public SearchAdapter(Context context, List<Object> listItems, 
                         OnItemClickListener itemClickListener, 
                         OnHeaderCheckedChangeListener headerCheckedListener,
                         OnHeaderClickListener headerClickListener) {
        this.context = context;
        this.listItems = listItems;
        this.itemClickListener = itemClickListener;
        this.headerCheckedListener = headerCheckedListener;
        this.headerClickListener = headerClickListener;
    }

    public void updateData(List<Object> newItems) {
        this.listItems = newItems;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (listItems.get(position) instanceof DateHeader) {
            return TYPE_HEADER;
        } else {
            return TYPE_ITEM;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_date_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.grid_item_search_result, parent, false);
            return new ItemViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, int position) {
        int viewType = getItemViewType(position);
        
        if (viewType == TYPE_HEADER) {
            HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
            final DateHeader dateHeader = (DateHeader) listItems.get(position);

            headerHolder.dateHeaderText.setText(dateHeader.getDateString());
            
            headerHolder.dateHeaderCheckbox.setOnCheckedChangeListener(null);
            headerHolder.dateHeaderCheckbox.setChecked(dateHeader.isChecked());
            headerHolder.dateHeaderCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (headerCheckedListener != null) {
                    headerCheckedListener.onHeaderCheckedChanged(dateHeader, isChecked);
                }
            });

            // Minimize/Expand Arrow
            headerHolder.arrowIcon.setRotation(dateHeader.isExpanded() ? 0f : 180f);
            headerHolder.arrowIcon.setOnClickListener(v -> {
                if (headerClickListener != null) {
                    headerClickListener.onHeaderClick(dateHeader);
                }
            });

        } else {
            final ItemViewHolder itemHolder = (ItemViewHolder) holder;
            final SearchResult item = (SearchResult) listItems.get(position);

            itemHolder.indexNumber.setText(String.valueOf(position + 1));
            itemHolder.exclusionOverlay.setVisibility(item.isExcluded() ? View.GONE : View.VISIBLE);
            itemHolder.thumbnailImage.setTag(item.getUri().toString());

            if (isMediaFile(item.getDisplayName())) {
                itemHolder.fileNameText.setVisibility(View.GONE);
            } else {
                itemHolder.fileNameText.setVisibility(View.VISIBLE);
                itemHolder.fileNameText.setText(item.getDisplayName());
            }

            String displayName = item.getDisplayName();
            int fallbackIcon = getIconForFileType(displayName);

            // LOGIC PRESERVATION: 
            // If it's a PDF or APK, use your ORIGINAL manual loading logic (restored below).
            // If it's Image/Video, use GLIDE for performance.
            
            boolean isPdfOrApk = displayName != null && (displayName.toLowerCase().endsWith(".pdf") || displayName.toLowerCase().endsWith(".apk"));

            if (isPdfOrApk) {
                // Restore Original Logic for PDFs and APKs
                itemHolder.thumbnailImage.setImageResource(fallbackIcon); // Set placeholder first
                
                thumbnailExecutor.execute(() -> {
                    final Bitmap thumbnail = createSpecialThumbnail(item); // Uses your restored methods
                    if (thumbnail != null && itemHolder.thumbnailImage.getTag().equals(item.getUri().toString())) {
                        itemHolder.thumbnailImage.post(() -> itemHolder.thumbnailImage.setImageBitmap(thumbnail));
                    }
                });

            } else {
                // Use Glide for everything else (Images, Videos) for the scrolling enhancement
                Glide.with(context)
                    .load(item.getUri())
                    .apply(new RequestOptions()
                        .placeholder(fallbackIcon)
                        .error(fallbackIcon)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop())
                    .into(itemHolder.thumbnailImage);
            }

            itemHolder.itemView.setOnClickListener(v -> {
                if (itemClickListener != null) itemClickListener.onItemClick(item);
            });

            itemHolder.itemView.setOnLongClickListener(v -> {
                if (itemClickListener != null) itemClickListener.onItemLongClick(item);
                return true;
            });
        }
    }

    @Override
    public int getItemCount() {
        return listItems.size();
    }

    private boolean isMediaFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
               lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp") ||
               lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".mkv") ||
               lower.endsWith(".webm") || lower.endsWith(".avi");
    }

    private int getIconForFileType(String fileName) {
        if (fileName == null) return android.R.drawable.ic_menu_info_details;
        String lower = fileName.toLowerCase();
        
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return android.R.drawable.ic_menu_save;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return android.R.drawable.ic_menu_agenda;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return android.R.drawable.ic_menu_slideshow;
        if (lower.endsWith(".pdf")) return android.R.drawable.ic_menu_view;
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return android.R.drawable.ic_menu_edit;
        if (lower.endsWith(".zip") || lower.endsWith(".rar")) return android.R.drawable.ic_menu_set_as;
        if (lower.endsWith(".mp3") || lower.endsWith(".wav")) return android.R.drawable.ic_media_play;
        if (isMediaFile(fileName)) return android.R.drawable.ic_menu_gallery;
        
        return android.R.drawable.ic_menu_info_details;
    }

    // --- RESTORED HELPER METHODS FOR PDF/APK LOGIC ---

    private Bitmap createSpecialThumbnail(SearchResult item) {
        Uri uri = item.getUri();
        String displayName = item.getDisplayName();
        if (displayName == null) return null;
        String lower = displayName.toLowerCase();

        if (lower.endsWith(".apk")) {
            String path = "file".equals(uri.getScheme()) ? uri.getPath() : null;
            if (path != null) return getApkIcon(path);
        }
        if (lower.endsWith(".pdf")) {
            return createPdfThumbnail(uri);
        }
        return null;
    }

    private Bitmap getApkIcon(String filePath) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(filePath, 0);
            if (pi != null) {
                ApplicationInfo appInfo = pi.applicationInfo;
                appInfo.sourceDir = filePath;
                appInfo.publicSourceDir = filePath;
                Drawable icon = appInfo.loadIcon(pm);
                return drawableToBitmap(icon);
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private Bitmap createPdfThumbnail(Uri uri) {
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            renderer = new PdfRenderer(pfd);
            page = renderer.openPage(0);
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (page != null) page.close();
                if (renderer != null) renderer.close();
                if (pfd != null) pfd.close();
            } catch (IOException ignored) {}
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 96;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 96;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    // --- ViewHolders ---

    public static class ItemViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnailImage;
        TextView indexNumber;
        View exclusionOverlay;
        TextView fileNameText;

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImage = itemView.findViewById(R.id.thumbnail_image);
            indexNumber = itemView.findViewById(R.id.index_number);
            exclusionOverlay = itemView.findViewById(R.id.exclusion_overlay);
            fileNameText = itemView.findViewById(R.id.file_name_text);
        }
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView dateHeaderText;
        CheckBox dateHeaderCheckbox;
        ImageView arrowIcon;

        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            dateHeaderText = itemView.findViewById(R.id.date_header_text);
            dateHeaderCheckbox = itemView.findViewById(R.id.date_header_checkbox);
            arrowIcon = itemView.findViewById(R.id.header_arrow);
        }
    }
}