package com.legacy.localsend.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 底部导航：3 项（接收/发送/设置），选中项图标包在 56x32dp 浅紫胶囊里。 */
public class BottomNav extends LinearLayout {

    public interface Listener {
        void onTabSelected(int index);
    }

    private static final String[] ICONS = { IconView.WIFI, IconView.SEND, IconView.GEAR };
    private static final int[] LABELS = {
            com.legacy.localsend.R.string.tab_receive,
            com.legacy.localsend.R.string.tab_send,
            com.legacy.localsend.R.string.tab_settings
    };

    private final Paint dividerPaint = new Paint();
    private final FrameLayout[] frames = new FrameLayout[3];
    private final IconView[] icons = new IconView[3];
    private final TextView[] labels = new TextView[3];
    private int selectedIndex = 0;
    private Listener listener;

    public BottomNav(Context context) {
        super(context);
        init(context);
    }

    public BottomNav(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xFFFFFFFF);
        setWillNotDraw(false);
        dividerPaint.setColor(0xFFE5E7EB);
        dividerPaint.setStrokeWidth(1f);
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(VERTICAL);
            item.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.CENTER_VERTICAL);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(android.view.View view) {
                    select(index, true);
                }
            });

            FrameLayout frame = new FrameLayout(context);
            IconView icon = new IconView(context);
            frame.addView(icon, new FrameLayout.LayoutParams(dp(context, 24), dp(context, 24),
                    android.view.Gravity.CENTER));
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                    dp(context, 56), dp(context, 32));
            item.addView(frame, frameParams);

            TextView label = new TextView(context);
            label.setText(LABELS[i]);
            label.setTextSize(12f);
            label.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(context, 2);
            item.addView(label, labelParams);

            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            addView(item, itemParams);

            frames[i] = frame;
            icons[i] = icon;
            labels[i] = label;
            icon.setIcon(ICONS[i], 0xFF9CA3AF);
        }
        select(0, false);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void select(int index) {
        select(index, false);
    }

    public void select(int index, boolean notifyListener) {
        if (index < 0 || index > 2) {
            return;
        }
        selectedIndex = index;
        for (int i = 0; i < 3; i++) {
            boolean active = i == index;
            frames[i].setBackgroundResource(active
                    ? com.legacy.localsend.R.drawable.shape_pill_container
                    : com.legacy.localsend.R.drawable.transparent);
            icons[i].setIconColor(active ? 0xFF2563EB : 0xFF9CA3AF);
            labels[i].setTextColor(active ? 0xFF2563EB : 0xFF9CA3AF);
            labels[i].getPaint().setFakeBoldText(active);
            labels[i].invalidate();
        }
        if (notifyListener && listener != null) {
            listener.onTabSelected(index);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(0, 0.5f, getWidth(), 0.5f, dividerPaint);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
