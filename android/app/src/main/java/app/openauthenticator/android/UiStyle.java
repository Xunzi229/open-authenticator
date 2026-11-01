package app.openauthenticator.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;

final class UiStyle {
    final int bg, surface, fill, line, text, muted, icon, accent, ok, danger, hidden;
    final boolean dark;

    UiStyle(boolean dark) {
        this.dark = dark;
        bg = Color.parseColor(dark ? "#000000" : "#f2f2f7");
        surface = Color.parseColor(dark ? "#1c1c1e" : "#ffffff");
        fill = Color.parseColor(dark ? "#3a3a3c" : "#e3e3e8");
        line = Color.parseColor(dark ? "#38383a" : "#e5e5ea");
        text = Color.parseColor(dark ? "#f5f5f7" : "#1c1c1e");
        muted = Color.parseColor(dark ? "#8e8e93" : "#8e8e93");
        icon = Color.parseColor(dark ? "#a0a0a5" : "#a6a6ad");
        accent = Color.parseColor(dark ? "#0a84ff" : "#007aff");
        ok = Color.parseColor(dark ? "#30d158" : "#34c759");
        danger = Color.parseColor(dark ? "#ff453a" : "#ff3b30");
        hidden = Color.parseColor(dark ? "#65656a" : "#c7c7cc");
    }

    static int dp(Context context, float size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static float sp(Context context, float size) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.getResources().getDisplayMetrics());
    }

    static GradientDrawable rounded(int color, Context context, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    static GradientDrawable outlined(int color, int border, Context context, int radius) {
        GradientDrawable drawable = rounded(color, context, radius);
        drawable.setStroke(dp(context, 1), border);
        return drawable;
    }

    static GradientDrawable avatar(Context context, int hue) {
        float[] hsv = {hue, .62f, .70f};
        int top = Color.HSVToColor(hsv);
        hsv[2] = .48f;
        int bottom = Color.HSVToColor(hsv);
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(dp(context, 9));
        return drawable;
    }

    static final class IconButton extends View {
        private final String kind;
        private final UiStyle style;
        private final int tint;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconButton(Context context, UiStyle style, String kind, String description, boolean filled, Runnable action) {
            this(context, style, kind, description, filled, action, style.icon);
        }

        IconButton(Context context, UiStyle style, String kind, String description, boolean filled, Runnable action, int tint) {
            super(context);
            this.kind = kind;
            this.style = style;
            this.tint = tint;
            setContentDescription(description);
            setFocusable(true);
            setClickable(true);
            if (filled) setBackground(rounded(style.fill, context, 9));
            setOnClickListener(view -> action.run());
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float unit = dp(getContext(), 0.9f);
            canvas.save();
            canvas.translate(getWidth() / 2f - 12 * unit, getHeight() / 2f - 12 * unit);
            canvas.scale(unit, unit);
            paint.setColor(tint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            switch (kind) {
                case "plus":
                    canvas.drawLine(12, 5, 12, 19, paint); canvas.drawLine(5, 12, 19, 12, paint); break;
                case "back":
                    canvas.drawLine(15, 5, 8, 12, paint); canvas.drawLine(8, 12, 15, 19, paint); break;
                case "search":
                    canvas.drawCircle(10.5f, 10.5f, 5.8f, paint); canvas.drawLine(15, 15, 20, 20, paint); break;
                case "upload":
                    canvas.drawLine(12, 4, 12, 15, paint); canvas.drawLine(8, 8, 12, 4, paint); canvas.drawLine(12, 4, 16, 8, paint);
                    canvas.drawLine(5, 16, 5, 19, paint); canvas.drawLine(5, 19, 19, 19, paint); canvas.drawLine(19, 19, 19, 16, paint); break;
                case "scan":
                    canvas.drawLine(4, 9, 4, 5, paint); canvas.drawLine(4, 5, 9, 5, paint);
                    canvas.drawLine(15, 5, 20, 5, paint); canvas.drawLine(20, 5, 20, 9, paint);
                    canvas.drawLine(4, 15, 4, 19, paint); canvas.drawLine(4, 19, 9, 19, paint);
                    canvas.drawLine(15, 19, 20, 19, paint); canvas.drawLine(20, 19, 20, 15, paint);
                    canvas.drawLine(8, 12, 16, 12, paint); break;
                case "settings":
                    canvas.drawCircle(12, 12, 3.2f, paint);
                    canvas.drawCircle(12, 12, 8, paint);
                    for (int i = 0; i < 8; i++) {
                        double angle = i * Math.PI / 4;
                        canvas.drawLine(12 + (float)Math.cos(angle) * 8, 12 + (float)Math.sin(angle) * 8,
                                12 + (float)Math.cos(angle) * 10, 12 + (float)Math.sin(angle) * 10, paint);
                    } break;
                case "copy":
                    canvas.drawRoundRect(8, 8, 19, 19, 1.2f, 1.2f, paint);
                    canvas.drawLine(5, 15, 5, 5, paint); canvas.drawLine(5, 5, 15, 5, paint); break;
                case "edit":
                    canvas.drawLine(5, 18, 8, 17.5f, paint); canvas.drawLine(8, 17.5f, 18, 7.5f, paint);
                    canvas.drawLine(18, 7.5f, 15.5f, 5, paint); canvas.drawLine(15.5f, 5, 5.5f, 15, paint);
                    canvas.drawLine(5.5f, 15, 5, 18, paint); canvas.drawLine(5, 20, 19, 20, paint); break;
                case "delete":
                    canvas.drawLine(5, 7, 19, 7, paint); canvas.drawLine(9, 5, 15, 5, paint);
                    canvas.drawLine(7, 9, 8, 19, paint); canvas.drawLine(8, 19, 16, 19, paint); canvas.drawLine(16, 19, 17, 9, paint);
                    canvas.drawLine(10, 11, 10.5f, 16, paint); canvas.drawLine(14, 11, 13.5f, 16, paint); break;
                case "qr":
                    canvas.drawRect(4, 4, 10, 10, paint); canvas.drawRect(14, 4, 20, 10, paint);
                    canvas.drawRect(4, 14, 10, 20, paint); canvas.drawLine(14, 14, 14, 20, paint);
                    canvas.drawLine(14, 14, 20, 14, paint); canvas.drawLine(18, 17, 20, 17, paint); break;
                case "lock":
                    canvas.drawRoundRect(5, 10, 19, 20, 1.5f, 1.5f, paint);
                    canvas.drawArc(new RectF(8, 3, 16, 15), 180, 180, false, paint); break;
            }
            canvas.restore();
        }
    }

    static final class CountdownRing extends View {
        private final UiStyle style;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int remain = 30;

        CountdownRing(Context context, UiStyle style) { super(context); this.style = style; setContentDescription("验证码剩余时间"); }
        void update() { remain = 30 - (int)((System.currentTimeMillis() / 1000) % 30); invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float center = getWidth() / 2f, radius = dp(getContext(), 14);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(getContext(), 2.5f));
            paint.setColor(style.line); canvas.drawCircle(center, getHeight() / 2f, radius, paint);
            paint.setColor(remain <= 5 ? style.danger : style.ok);
            canvas.drawArc(new RectF(center - radius, getHeight() / 2f - radius, center + radius, getHeight() / 2f + radius),
                    -90, remain * 12f, false, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(style.muted);
            paint.setTextSize(sp(getContext(), 10)); paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(String.valueOf(remain), center, getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
        }
    }
}
