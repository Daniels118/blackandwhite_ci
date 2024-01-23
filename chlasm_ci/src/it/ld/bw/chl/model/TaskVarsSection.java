package it.ld.bw.chl.model;

import java.util.ArrayList;

import it.ld.utils.EndianDataInputStream;
import it.ld.utils.EndianDataOutputStream;

public class TaskVarsSection extends StructArray<TaskVar> {
	public int minItems;
	
	public TaskVarsSection(int minItems) {
		this.minItems = minItems;
	}
	
	@Override
	public Class<TaskVar> getItemClass() {
		return TaskVar.class;
	}
	
	@Override
	public TaskVar createItem() {
		return new TaskVar();
	}
	
	@Override
	public void read(EndianDataInputStream str) throws Exception {
		int count = str.readInt();
		items = new ArrayList<>(count);
		final int numRead = Math.max(minItems, count);
		for (int i = 0; i < numRead; i++) {
			TaskVar e = readItem(str, i);
			if (i < count) {
				items.add(e);
			}
		}
	}
	
	@Override
	public void write(EndianDataOutputStream str) throws Exception {
		str.writeInt(items.size());
		for (Struct struct : items) {
			struct.write(str);
		}
		TaskVar nullItem = new TaskVar();
		for (int i = items.size(); i < minItems; i++) {
			nullItem.write(str);
		}
	}
	
	@Override
	public String toString() {
		return "TaskVars[" + items.size() + "]";
	}
}
