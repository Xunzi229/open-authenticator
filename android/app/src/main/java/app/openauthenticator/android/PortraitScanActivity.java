package app.openauthenticator.android;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.Size;

/** Portrait camera screen with a centered QR guide and simple scan controls. */
public final class PortraitScanActivity extends CaptureActivity {
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView label(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    @Override protected DecoratedBarcodeView initializeContent() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        DecoratedBarcodeView scanner = new DecoratedBarcodeView(this);
        scanner.getBarcodeView().setFramingRectSize(new Size(dp(260), dp(260)));
        scanner.getViewFinder().setMaskColor(0xb3000000);
        scanner.getViewFinder().setLaserVisibility(false);
        scanner.setStatusText("");
        root.addView(scanner, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new ScanFrameView(scanner), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView close = label("‹", 32);
        close.setContentDescription("关闭扫码"); close.setOnClickListener(view -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = label("扫描二维码", 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        TextView hint = label("将验证器二维码放入框内，自动识别", 14);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM);
        hintParams.bottomMargin = dp(112); root.addView(hint, hintParams);

        Button torch = new Button(this);
        torch.setText("打开手电筒"); torch.setTextSize(14); torch.setAllCaps(false);
        torch.setTextColor(Color.WHITE);
        GradientDrawable torchBackground = new GradientDrawable();
        torchBackground.setColor(0x88444444); torchBackground.setCornerRadius(dp(24));
        torch.setBackground(torchBackground);
        final boolean[] torchOn = {false};
        torch.setOnClickListener(view -> {
            try {
                if (torchOn[0]) scanner.setTorchOff(); else scanner.setTorchOn();
                torchOn[0] = !torchOn[0];
                torch.setText(torchOn[0] ? "关闭手电筒" : "打开手电筒");
            } catch (RuntimeException error) { Toast.makeText(this, "手电筒不可用", Toast.LENGTH_SHORT).show(); }
        });
        FrameLayout.LayoutParams torchParams = new FrameLayout.LayoutParams(dp(150), dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        torchParams.bottomMargin = dp(48); root.addView(torch, torchParams);

        setContentView(root);
        return scanner;
    }

    private final class ScanFrameView extends View {
        private final DecoratedBarcodeView scanner;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScanFrameView(DecoratedBarcodeView scanner) { super(PortraitScanActivity.this); this.scanner = scanner; }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Rect frame = scanner.getBarcodeView().getFramingRect();
            if (frame != null) {
                int corner = dp(25);
                paint.setColor(0xff4ee08a); paint.setStrokeWidth(dp(4)); paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(frame.left, frame.top + corner, frame.left, frame.top, paint);
                canvas.drawLine(frame.left, frame.top, frame.left + corner, frame.top, paint);
                canvas.drawLine(frame.right - corner, frame.top, frame.right, frame.top, paint);
                canvas.drawLine(frame.right, frame.top, frame.right, frame.top + corner, paint);
                canvas.drawLine(frame.left, frame.bottom - corner, frame.left, frame.bottom, paint);
                canvas.drawLine(frame.left, frame.bottom, frame.left + corner, frame.bottom, paint);
                canvas.drawLine(frame.right - corner, frame.bottom, frame.right, frame.bottom, paint);
                canvas.drawLine(frame.right, frame.bottom - corner, frame.right, frame.bottom, paint);
                float progress = (System.currentTimeMillis() % 2200L) / 2200f;
                float y = frame.top + dp(12) + (frame.height() - dp(24)) * progress;
                paint.setColor(0x994ee08a); paint.setStrokeWidth(dp(2));
                canvas.drawLine(frame.left + dp(16), y, frame.right - dp(16), y, paint);
            }
            postInvalidateDelayed(50);
        }
    }
}
