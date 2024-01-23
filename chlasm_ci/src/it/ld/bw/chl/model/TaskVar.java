package it.ld.bw.chl.model;

import it.ld.utils.EndianDataInputStream;
import it.ld.utils.EndianDataOutputStream;

public class TaskVar extends Struct {
	public static final int SIZE = 8;
	
	public int taskId;
	public int varId;
	
	@Override
	public void read(EndianDataInputStream str) throws Exception {
		taskId = str.readInt();
		varId = str.readInt();
	}

	@Override
	public void write(EndianDataOutputStream str) throws Exception {
		str.writeInt(taskId);
		str.writeInt(varId);
	}
	
	@Override
	public String toString() {
		return "{task=" + taskId + ", var=" + varId + "}";
	}
}
