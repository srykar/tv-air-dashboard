package com.gathin.tvhome;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.TextView;

/** Compact readout with the unit on the value's baseline, without shrinking the number. */
final class AirMetricText {
    private AirMetricText() { }

    static void set(TextView view, String value, String unit) {
        SpannableString text = new SpannableString(value + "\u2009" + unit);
        int start = value.length() + 1;
        text.setSpan(new RelativeSizeSpan(0.34f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(0xB3FFFFFF), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
        view.setContentDescription(value + ("US PM".equals(unit)
                ? " US particle AQI" : " micrograms per cubic metre"));
    }
}
