package it.ld.bw.chl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.ld.bw.chl.lang.CHLCompiler;
import it.ld.bw.chl.lang.Project;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.ObjectCode;
import it.ld.bw.chl.model.Struct;
import it.ld.utils.EndianDataInputStream;
import it.ld.utils.EndianDataOutputStream;

public class Make {
	private PrintStream out;
	
	private CHLCompiler.Options compilerOptions = new CHLCompiler.Options();
	private CHLLinker.Options linkerOptions = new CHLLinker.Options();
	
	public CHLCompiler.Options getCompilerOptions() {
		return compilerOptions;
	}
	
	public void setCompilerOptions(CHLCompiler.Options options) {
		this.compilerOptions = options;
	}
	
	public CHLLinker.Options getLinkerOptions() {
		return linkerOptions;
	}
	
	public void setLinkerOptions(CHLLinker.Options linkerOptions) {
		this.linkerOptions = linkerOptions;
	}
	
	public Make() {
		this(System.out);
	}
	
	public Make(PrintStream outStream) {
		this.out = outStream;
	}
	
	public CHLFile make(Project project) throws Exception {
		if (project.objPath == null) {
			project.objPath = project.sourcePath.resolve("bin");
		}
		if (!project.objPath.toFile().isDirectory()) {
			project.objPath.toFile().mkdir();
		}
		final CHLCompiler compiler = new CHLCompiler(out);
		compiler.setOptions(compilerOptions);
		//Load compiled constants
		File constantsFile = project.objPath.resolve("_constants.bin").toFile();
		if (constantsFile.exists()) {
			Constants constants = new Constants();
			constants.read(constantsFile);
			compiler.addConstants(constants.items);
		}
		//Load header files and project constants
		for (File file : project.cHeaders) {
			compiler.loadHeader(file);
		}
		for (File file : project.infoFiles) {
			compiler.loadInfo(file);
		}
		compiler.addConstants(project.constants);
		//Compile files
		List<File> objfiles = new ArrayList<>(project.sources.size());
		try {
			for (File file : project.sources) {
				String objname = basename(file.getName()) + ".o";
				File objfile = project.objPath.resolve(objname).toFile();
				if (project.clean && objfile.exists()) {
					objfile.delete();
				}
				if (!objfile.exists() || objfile.lastModified() < file.lastModified()) {
					out.println("compiling " + file.getName());
					ObjectCode objcode = compiler.compile(file);
					objcode.write(objfile);
				}
				objfiles.add(objfile);
			}
		} finally {
			//Write compiled constants
			Constants constants = new Constants();
			constants.items = compiler.getDefinedConstants();
			constants.write(constantsFile);
		}
		//Link
		out.println("linking...");
		final CHLLinker linker = new CHLLinker(out);
		linker.setOptions(linkerOptions);
		CHLFile chl = linker.link(objfiles);
		chl.validate(out);
		chl.write(project.output);
		return chl;
	}
	
	private static String basename(String name) {
		int p = name.lastIndexOf('.');
		if (p >= 0) {
			return name.substring(0, p);
		}
		return name;
	}
	
	
	private static class Constants extends Struct {
		public Map<String, Integer> items = new HashMap<>();
		
		public void read(File file) throws Exception {
			try (EndianDataInputStream str = new EndianDataInputStream(new BufferedInputStream(new FileInputStream(file)));) {
				read(str);
			}
		}
		
		public void write(File file) throws Exception {
			try (EndianDataOutputStream str = new EndianDataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));) {
				write(str);
			}
		}
		
		@Override
		public void read(EndianDataInputStream str) throws Exception {
			items = readMapOfStringInt(str);
		}

		@Override
		public void write(EndianDataOutputStream str) throws Exception {
			writeMapOfStringInt(str, items);
		}
	}
}
