package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.electroheavenvn.zaloxposed.utilities.BackgroundUtils;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.ThemeColors;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/** Picker for the module accent color, including Monet and a custom hex value. */
public final class AccentColorDialog
{
    private AccentColorDialog() { }

    public static void show(Context context)
    {
        Activity activity = BackgroundUtils.findActivity(context);
        if (activity == null)
            return;

        int textColor = resolve(activity, android.R.attr.textColorPrimary, 0xFF111111);
        int secondary = resolve(activity, android.R.attr.textColorSecondary, 0xFF757575);

        GridView grid = new GridView(activity);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(activity, 12));
        grid.setHorizontalSpacing(dp(activity, 8));
        grid.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        grid.setAdapter(new ColorAdapter(activity, textColor, secondary));

        new AlertDialog.Builder(activity)
            .setTitle(Utils.IsEnglish() ? "Accent color" : "Màu nhấn")
            .setView(grid)
            .setNegativeButton(Utils.IsEnglish() ? "Cancel" : "Hủy", null)
            .show();
    }

    private static final class ColorAdapter extends BaseAdapter
    {
        private final Activity activity;
        private final int textColor;
        private final int secondary;

        ColorAdapter(Activity activity, int textColor, int secondary)
        {
            this.activity = activity;
            this.textColor = textColor;
            this.secondary = secondary;
        }

        @Override
        public int getCount()
        {
            return ThemeColors.VALUES.length;
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
            cell.setPadding(dp(activity, 4), dp(activity, 6), dp(activity, 4), dp(activity, 6));

            ImageView swatch = new ImageView(activity);
            int color = position == ThemeColors.VALUES.length - 2
                ? ThemeColors.getAccent(activity)
                : ThemeColors.parse(ThemeColors.VALUES[position], 0xFF0068FF);
            if (position == ThemeColors.VALUES.length - 1)
            {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setStroke(dp(activity, 2), secondary);
                swatch.setImageDrawable(ring);
            }
            else
            {
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(color);
                swatch.setImageDrawable(circle);
            }
            int size = dp(activity, 44);
            cell.addView(swatch, new LinearLayout.LayoutParams(size, size));

            TextView label = new TextView(activity);
            label.setText(ThemeColors.NAMES[position]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(textColor);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(activity, 6), 0, 0);
            cell.addView(label);

            final int selected = position;
            cell.setOnClickListener(v -> select(selected));
            return cell;
        }

        private void select(int position)
        {
            if (position == ThemeColors.VALUES.length - 1)
            {
                askCustom();
                return;
            }
            Config.setAccentColor(ThemeColors.VALUES[position]);
            Toast.makeText(activity,
                Utils.IsEnglish() ? "Accent color saved. Restart Zalo to apply."
                    : "Đã lưu màu nhấn. Khởi động lại Zalo để áp dụng.",
                Toast.LENGTH_SHORT).show();
        }

        private void askCustom()
        {
            final EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setHint("#6750A4");
            input.setText(Config.getAccentColor().startsWith("#") ? Config.getAccentColor() : "");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            new AlertDialog.Builder(activity)
                .setTitle(Utils.IsEnglish() ? "Custom hex color" : "Màu hex tuỳ chỉnh")
                .setView(input)
                .setNegativeButton(Utils.IsEnglish() ? "Cancel" : "Hủy", null)
                .setPositiveButton(Utils.IsEnglish() ? "Save" : "Lưu", (dialog, which) ->
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
                        Utils.IsEnglish() ? "Accent color saved. Restart Zalo to apply."
                            : "Đã lưu màu nhấn. Khởi động lại Zalo để áp dụng.",
                        Toast.LENGTH_SHORT).show();
                })
                .show();
        }
    }

    private static int resolve(Context context, int attribute, int fallback)
    {
        try
        {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attribute, value, true))
            {
                if (value.resourceId != 0)
                    return context.getResources().getColor(value.resourceId);
                return value.data;
            }
        }
        catch (Throwable ignored) { }
        return fallback;
    }

    private static int dp(Context context, int value)
    {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
