package com.uac.spoofer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

public class InnerScrollView extends ScrollView {
    private float lastY;

    public InnerScrollView(Context context) {
        super(context);
        init();
    }

    public InnerScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InnerScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setVerticalScrollBarEnabled(true);
        setScrollbarFadingEnabled(false);
        setFillViewport(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastY = event.getY();
            disallowParentIntercept(canScrollVertically(-1) || canScrollVertically(1));
        } else if (action == MotionEvent.ACTION_MOVE) {
            float y = event.getY();
            float dy = y - lastY;
            lastY = y;
            boolean canScrollInGestureDirection = dy < 0
                    ? canScrollVertically(1)
                    : canScrollVertically(-1);
            disallowParentIntercept(canScrollInGestureDirection);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            disallowParentIntercept(false);
        }
        return super.dispatchTouchEvent(event);
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }
}

