package it.ld.bw.chl.exceptions;

public class CompileException extends Exception {
	private static final long serialVersionUID = 1L;
	
	private final String script;
	private final String sourceFilename;
	private final int instructionAddress;
	
	public CompileException(String msg) {
		super(msg);
		this.script = null;
		this.sourceFilename = null;
		this.instructionAddress = 0;
	}
	
	public CompileException(Exception cause) {
		super(cause.getMessage(), cause);
		this.script = null;
		this.sourceFilename = null;
		this.instructionAddress = 0;
	}
	
	public CompileException(String script, String filename, int instructionAddress, Exception cause) {
		super(cause.getMessage()+" at instruction "+instructionAddress+" in script "+script+" in file "+filename, cause);
		this.script = script;
		this.sourceFilename = filename;
		this.instructionAddress = instructionAddress;
	}
	
	public String getScript() {
		return script;
	}
	
	public String getSourceFilename() {
		return sourceFilename;
	}
	
	public int getInstructionAddress() {
		return instructionAddress;
	}
}
