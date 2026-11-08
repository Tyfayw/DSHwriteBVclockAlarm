package com.bvalarm.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Switch;

import java.util.List;

public class AlarmAdapter extends BaseAdapter {

    public interface Listener {
        void onToggle(Alarm alarm, boolean enabled);
    }

    private final LayoutInflater inflater;
    private final List<Alarm> items;
    private final Listener listener;
    private final int colorAccent;
    private final int colorTertiary;

    public AlarmAdapter(Context context, List<Alarm> items, Listener listener) {
        this.inflater = LayoutInflater.from(context);
        this.items = items;
        this.listener = listener;
        this.colorAccent = context.getColor(R.color.accent);
        this.colorTertiary = context.getColor(R.color.text_tertiary);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Alarm getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        Holder holder;
        if (view == null) {
            view = inflater.inflate(R.layout.item_alarm, parent, false);
            holder = new Holder();
            holder.time = (TextView) view.findViewById(R.id.time);
            holder.days = (TextView) view.findViewById(R.id.days);
            holder.label = (TextView) view.findViewById(R.id.label);
            holder.badge = (TextView) view.findViewById(R.id.src_badge);
            holder.ringName = (TextView) view.findViewById(R.id.ring_name);
            holder.toggle = (Switch) view.findViewById(R.id.toggle);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }

        final Alarm alarm = items.get(position);
        holder.time.setText(alarm.timeText());
        holder.days.setText(alarm.daysSummary());

        if (alarm.label != null && alarm.label.length() > 0) {
            holder.label.setText(alarm.label);
            holder.label.setVisibility(View.VISIBLE);
        } else {
            holder.label.setVisibility(View.GONE);
        }

        boolean bv = alarm.hasBvRingtone();
        holder.badge.setText(bv ? "B站" : "系统");
        holder.badge.setTextColor(bv ? colorAccent : colorTertiary);
        String ringName = bv
                ? (alarm.ringTitle == null || alarm.ringTitle.isEmpty() ? alarm.bvid : alarm.ringTitle)
                : "默认铃声";
        holder.ringName.setText(ringName);

        float alpha = alarm.enabled ? 1f : 0.45f;
        holder.time.setAlpha(alpha);
        holder.days.setAlpha(alpha);
        holder.label.setAlpha(alpha);
        holder.badge.setAlpha(alpha);
        holder.ringName.setAlpha(alpha);

        holder.toggle.setOnCheckedChangeListener(null);
        holder.toggle.setChecked(alarm.enabled);
        holder.toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (listener != null) {
                    listener.onToggle(alarm, isChecked);
                }
            }
        });
        return view;
    }

    private static class Holder {
        TextView time;
        TextView days;
        TextView label;
        TextView badge;
        TextView ringName;
        Switch toggle;
    }
}
