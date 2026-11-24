package dev.locus.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

final class LocusChrome {
    private LocusChrome() {}

    static GradientDrawable surface(Context context, int fill, int edge, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(context.getResources().getDisplayMetrics().density * radiusDp);
        if (edge != 0) shape.setStroke(1, edge);
        return shape;
    }

    static RippleDrawable action(Context context, boolean primary) {
        return new RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.ripple)),
                primary ? surface(context, context.getColor(R.color.accent_surface), 0, 8) : null,
                surface(context, 0xffffffff, 0, 8));
    }

    static ColorStateList actionText(Context context, boolean primary) {
        return new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        }, new int[]{context.getColor(R.color.text_disabled),
                context.getColor(primary ? R.color.accent : R.color.text_primary)});
    }

    static ImageButton iconAction(Context context, int icon, String label) {
        ImageButton button = new ImageButton(context);
        button.setBackground(action(context, false));
        button.setImageTintList(actionText(context, false));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        setIcon(button, icon, label);
        return button;
    }

    static void setIcon(ImageButton button, int icon, String label) {
        button.setImageResource(icon);
        labelIcon(button, label);
    }

    static void labelIcon(ImageButton button, String label) {
        button.setContentDescription(label);
        button.setTooltipText(label);
    }

    static void fitForm(AlertDialog dialog) {
        Context context = dialog.getContext();
        if (context.getResources().getConfiguration().screenWidthDp >= 600) {
            int width = Math.round(540 * context.getResources().getDisplayMetrics().density);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
