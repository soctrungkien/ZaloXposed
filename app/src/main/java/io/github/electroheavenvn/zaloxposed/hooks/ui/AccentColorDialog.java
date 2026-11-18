package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.electroheavenvn.zaloxposed.utilities.BackgroundUtils;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.ThemeColors;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/** Polished picker for the module accent color (presets, Monet, custom hex). */
public final class AccentColorDialog
{
    private final Activity activity;
    private final int textColor;
    private final int secondary;
    private final int accent;
    private final int background;
    private final int card;
    private Dialog dialog;

    private AccentColorDialog(Activity activity)
    {
        this.activity = activity;
        this.textColor = resolve(android.R.attr.textColorPrimary, 0xFF111111);
        this.secondary = resolve(android.R.attr.textColorSecondary, 0xFF757575);
        this.accent = ThemeColors.getAccent(activity);
        this.background = resolve(android.R.attr.colorBackgroundFloating, 0xFFFFFFFF);
        this.card = withAlpha(textColor, 0x0A);
    }

    public static void show(Context context)
    {
        Activity activity = BackgroundUtils.findActivity(context);
        if (activity != null)
            new AccentColorDialog(activity).showDialog();
    }

    private void showDialog()
    {
        boolean english = Utils.IsEnglish();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(background, dp(24), withAlpha(textColor, 0x12)));
        root.setClipToOutline(true);

        // Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(12), dp(8));
        TextView title = new TextView(activity);
        title.setText(english ? "Accent color" : "Màu nhấn");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(textColor);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setTextColor(secondary);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(8), dp(4), dp(8), dp(4));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(header);

        // Current color preview
        LinearLayout preview = new LinearLayout(activity);
        preview.setOrientation(LinearLayout.HORIZONTAL);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(20), dp(8), dp(20), dp(16));
        ImageView circle = new ImageView(activity);
        circle.setImageDrawable(roundColor(accent, dp(56)));
        preview.addView(circle, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.setMargins(dp(14), 0, 0, 0);
        info.setLayoutParams(infoParams);
        TextView label = new TextView(activity);
        label.setText(ThemeColors.currentLabel(activity));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(textColor);
        info.addView(label);
        TextView hint = new TextView(activity);
        hint.setText(english ? "Tap a color to apply" : "Chạm vào màu để áp dụng");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(secondary);
        info.addView(hint);
        preview.addView(info);
        root.addView(preview);

        root.addView(divider());

        // Swatch grid
        GridView grid = new GridView(activity);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(8));
        grid.setPadding(dp(14), dp(12), dp(14), dp(8));
        grid.setSelector(android.R.color.transparent);
        grid.setAdapter(new ColorAdapter());
        root.addView(grid, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        root.addView(divider());

        // Footer
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(20), dp(12), dp(16), dp(16));
        TextView note = new TextView(activity);
        note.setText(english ? "Restart Zalo to apply" : "Khởi động lại Zalo để áp dụng");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(secondary);
        note.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footer.addView(note);
        TextView custom = new TextView(activity);
        custom.setText(english ? "Custom hex…" : "Màu tuỳ chỉnh…");
        custom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        custom.setTypeface(Typeface.DEFAULT_BOLD);
        custom.setTextColor(accent);
        custom.setPadding(dp(10), dp(8), dp(10), dp(8));
        custom.setOnClickListener(v -> askCustom());
        footer.addView(custom);
        root.addView(footer);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null)
        {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int width = (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.9f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private View divider()
    {
        View view = new View(activity);
        view.setBackgroundColor(withAlpha(textColor, 0x12));
        view.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return view;
    }

    private final class ColorAdapter extends BaseAdapter
    {
        @Override
        public int getCount()
        {
            return ThemeColors.VALUES.length - 1; // custom lives in the footer
        }

        @Override
        public Object getItem(int position)
        {
            return ThemeColors.VALUES[position];
        }

        @Override
        public long getItemId(int position)
        {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent)
        {
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(rounded(card, dp(14), 0));
            cell.setPadding(dp(4), dp(10), dp(4), dp(10));

            String value = ThemeColors.VALUES[position];
            boolean monet = ThemeColors.MONET.equalsIgnoreCase(value);
            int color = monet ? ThemeColors.getMonetAccent(activity)
                : ThemeColors.parse(value, 0xFF0068FF);
            if (color == 0)
                color = 0xFF6750A4;

            FrameLayout swatch = new FrameLayout(activity);
            ImageView circle = new ImageView(activity);
            circle.setImageDrawable(roundColor(color, dp(40)));
            swatch.addView(circle, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
            if (value.equalsIgnoreCase(Config.getAccentColor()))
            {
                TextView check = new TextView(activity);
                check.setText("✓");
                check.setTextColor(Color.WHITE);
                check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                check.setGravity(Gravity.CENTER);
                check.setShadowLayer(dp(2), 0, 0, 0x99000000);
                swatch.addView(check, new FrameLayout.LayoutParams(
                    dp(40), dp(40), Gravity.CENTER));
            }
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(
                dp(44), dp(44));
            swatchParams.setMargins(0, 0, 0, dp(6));
            cell.addView(swatch, swatchParams);

            TextView name = new TextView(activity);
            name.setText(ThemeColors.NAMES[position]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            name.setTextColor(textColor);
            name.setGravity(Gravity.CENTER);
            cell.addView(name);

            cell.setOnClickListener(v ->
            {
                Config.setAccentColor(value);
                Toast.makeText(activity,
                    Utils.IsEnglish() ? "Accent color saved" : "Đã lưu màu nhấn",
                    Toast.LENGTH_SHORT).show();
                dismiss();
            });
            return cell;
        }
    }

    private void askCustom()
    {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("#6750A4");
        String current = Config.getAccentColor();
        input.setText(current.startsWith("#") ? current : "");
        input.setTextColor(textColor);
        new AlertDialog.Builder(activity)
            .setTitle(Utils.IsEnglish() ? "Custom hex color" : "Màu hex tuỳ chỉnh")
            .setView(input)
            .setNegativeButton(Utils.IsEnglish() ? "Cancel" : "Hủy", null)
            .setPositiveButton(Utils.IsEnglish() ? "Save" : "Lưu", (d, which) ->
            {
                String value = input.getText().toString().trim();
                if (!value.startsWith("#"))
                    value = "#" + value;
                if (ThemeColors.parse(value, 0) == 0 && !"#000000".equalsIgnoreCase(value))
                {
                    Toast.makeText(activity,
                        Utils.IsEnglish() ? "Invalid color" : "Màu không hợp lệ",
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                Config.setAccentColor(value.toUpperCase());
                Toast.makeText(activity,
                    Utils.IsEnglish() ? "Accent color saved" : "Đã lưu màu nhấn",
                    Toast.LENGTH_SHORT).show();
                dismiss();
            })
            .show();
    }

    private void dismiss()
    {
        if (dialog != null)
        {
            try
            {
                dialog.dismiss();
            }
            catch (Throwable ignored) { }
            dialog = null;
        }
    }

    private int resolve(int attribute, int fallback)
    {
        try
        {
            TypedValue value = new TypedValue();
            if (activity.getTheme().resolveAttribute(attribute, value, true))
            {
                if (value.resourceId != 0)
                    return activity.getResources().getColor(value.resourceId);
                return value.data;
            }
        }
        catch (Throwable ignored) { }
        return fallback;
    }

    private GradientDrawable rounded(int color, int radius, int stroke)
    {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != 0)
            drawable.setStroke(1, stroke);
        return drawable;
    }

    private GradientDrawable roundColor(int color, int size)
    {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private static int withAlpha(int color, int alpha)
    {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value)
    {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
