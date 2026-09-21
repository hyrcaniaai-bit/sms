package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;

// Mirrors ListAdapter's shape (ForwardingConfig rows on the main screen) but
// for the reusable Destination list on DestinationsActivity.
public class DestinationsListAdapter extends ArrayAdapter<Destination> {
    final private ArrayList<Destination> dataSet;
    final private Context context;

    public DestinationsListAdapter(ArrayList<Destination> data, Context context) {
        super(context, R.layout.list_item_destination, data);
        this.dataSet = data;
        this.context = context;
    }

    @Override
    public int getCount() {
        return this.dataSet.size();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View row = convertView;
        if (row == null) {
            row = inflater.inflate(R.layout.list_item_destination, parent, false);
        }

        Destination destination = getItem(position);

        TextView summary = row.findViewById(R.id.text_destination_summary);
        summary.setText(destination.getSummary(context));

        View editButton = row.findViewById(R.id.edit_button);
        editButton.setTag(R.id.edit_button, position);
        editButton.setOnClickListener(this::onEditClick);

        View deleteButton = row.findViewById(R.id.delete_button);
        deleteButton.setTag(R.id.delete_button, position);
        deleteButton.setOnClickListener(this::onDeleteClick);

        return row;
    }

    public void onEditClick(View view) {
        final int position = (int) view.getTag(R.id.edit_button);
        ((DestinationsActivity) context).showEditDialog(getItem(position));
    }

    public void onDeleteClick(View view) {
        final int position = (int) view.getTag(R.id.delete_button);
        ((DestinationsActivity) context).confirmDelete(getItem(position), this);
    }
}
