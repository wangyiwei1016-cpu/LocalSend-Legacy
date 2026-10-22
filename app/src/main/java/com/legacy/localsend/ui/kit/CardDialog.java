package com.legacy.localsend.ui.kit;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.legacy.localsend.R;
import com.legacy.localsend.ui.Theme;

/**
 * 自绘卡片对话框：透明 window + 圆角卡片（20dp 圆角 + 1dp 描边），
 * 标题 / 可滚动内容 / 右对齐文字按钮，颜色跟随 Theme（支持深色模式）。
 */
public class CardDialog extends Dialog {

    private final LinearLayout card;
    private final LinearLayout body;
    private final LinearLayout buttonRow;
    private final TextView titleView;
    private final MaxHeightScrollView scroll;

    public CardDialog(Context context, CharSequence title) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            if (Build.VERSION.SDK_INT >= 14) {
                window.setDimAmount(0.45f);
            }
        }
        int pad = dp(24);
        FrameLayout root = new FrameLayout(context);
        root.setPadding(pad, dp(28), pad, dp(28));

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundDrawable(Theme.strokedRoundRect(Theme.surface, Theme.stroke, 20));
        card.setPadding(dp(20), dp(20), dp(20), dp(12));

        titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Theme.text);
        titleView.setTextSize(18f);
        titleView.getPaint().setFakeBoldText(true);
        card.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new MaxHeightScrollView(context);
        scroll.setMaxHeight(Math.round(getContext().getResources().getDisplayMetrics().heightPixels * 0.5f));
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(10);
        card.addView(scroll, scrollParams);

        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);
        card.addView(buttonRow, rowParams);

        root.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        setCancelable(true);
        if (Build.VERSION.SDK_INT >= 11) {
            setCanceledOnTouchOutside(false);
        }
    }

    public void setBodyView(View view) {
        body.removeAllViews();
        if (view != null) {
            body.addView(view, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    public void setBodyText(CharSequence text) {
        TextView textView = new TextView(getContext());
        textView.setText(text);
        textView.setTextColor(Theme.textSecondary);
        textView.setTextSize(14f);
        textView.setLineSpacing(dp(4), 1f);
        setBodyView(textView);
    }

    public void setBodyScrollable(boolean scrollable) {
        if (!scrollable) {
            scroll.setMaxHeight(Integer.MAX_VALUE);
        }
    }

    public void addButton(CharSequence text, final DialogInterface.OnClickListener listener) {
        TextView button = new TextView(getContext());
        button.setText(text);
        button.setTextColor(Theme.primary);
        button.setTextSize(15f);
        button.getPaint().setFakeBoldText(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(48));
        button.setBackgroundDrawable(Theme.dialogButtonDrawable());
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) {
                    listener.onClick(CardDialog.this, 0);
                } else {
                    dismiss();
                }
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        params.leftMargin = dp(8);
        buttonRow.addView(button, params);
    }

    public void addDismissButton(CharSequence text) {
        addButton(text, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
    }

    @Override
    public void show() {
        try {
            super.show();
        } catch (Throwable ignored) {
            // 窗口不可用时忽略
        }
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    public void setTitleText(CharSequence title) {
        titleView.setText(title);
    }

    private int dp(int value) {
        return (int) (value * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }
}

