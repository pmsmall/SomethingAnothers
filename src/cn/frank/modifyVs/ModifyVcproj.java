package cn.frank.modifyVs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.QName;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.dom4j.tree.DefaultDocument;

public class ModifyVcproj {

	private HashMap<String, String> userMacros = new HashMap<>();
	private HashSet<String> vcprojs = new HashSet<>();
	private HashMap<String, ArrayList<String>> slns = new HashMap<>();
	private static final String UUID_PATTERN = "[a-zA-Z0-9]{8}-([a-zA-Z0-9]{4}-){3}[a-zA-Z0-9]{12}";
	private static final String NAME_PATTERN = "[a-zA-Z0-9\\s_]+";
	private static final String NIX_PATH_PATTERN = "[a-zA-Z0-9\\s_/:$\\(\\)\\\\.]+";
	private static final String VCXPROJ_PATTERN = "[a-zA-Z0-9_\\s\\\\/]+\\.vcxproj";
	private static final String USER_MARCO_FILE = "Microsoft.Cpp.Win32.user";
	private final String JAVA_HOME_x86;
	private final static String XMLNS_NAMESPACE = "http://schemas.microsoft.com/developer/msbuild/2003";

	private final String CygwinHome;
	private final String CygwinMakeDir;
	private final String BuildDir;
	private final String TotalRoot;

	public ModifyVcproj(String CygwinHome, String CygwinMakeDir, String TotalRoot, String BuildDir, String JAVA_HOME) {
		this.CygwinHome = CygwinHome;
		this.CygwinMakeDir = CygwinMakeDir;
		this.TotalRoot = TotalRoot;
		this.BuildDir = BuildDir;
		this.JAVA_HOME_x86 = JAVA_HOME;
		Arrays.sort(SDK_MARCO_LIST);
		System.arraycopy(SDK_MARCO_LIST, 0, BUF_SDK_MARCO_LIST, 1, SDK_MARCO_LIST.length);
	}

	public int modifySln(String path) {
		File file = new File(path);
		ArrayList<File> slns = new ArrayList<>();
		if (path.endsWith(".sln")) {
			slns.add(file);
		} else {
			if (file.isDirectory()) {
				for (File sln : file.listFiles((File pathname) -> {
					return pathname.getName().endsWith(".sln");
				})) {
					slns.add(sln);
				}
			}
		}
		int count = 0;
		for (File sln : slns) {
			if (modifySln(sln))
				count++;
		}
		return count;
	}

	public Document createUserMacros(Map<String, String> macros) {
		Document doc = new DefaultDocument();
		doc.setXMLEncoding("utf-8");
		Element project = doc.addElement("Project", XMLNS_NAMESPACE);
		project.addAttribute(new QName("ToolsVersion"), "4.0");
		Element PropertySheets = project.addElement("ImportGroup");
		PropertySheets.addAttribute(new QName("Label"), "PropertySheets");
		Element UserMacros = project.addElement("PropertyGroup");
		UserMacros.addAttribute(new QName("Label"), "UserMacros");
		for (Map.Entry<String, String> e : macros.entrySet()) {
			UserMacros.addElement(e.getKey()).setText(e.getValue());
		}
		project.addElement("PropertyGroup");
		project.addElement("ItemDefinitionGroup");
		Element ItemGroup = project.addElement("ItemGroup");

		for (String key : macros.keySet()) {
			Element BuildMacro = ItemGroup.addElement("BuilMacro");
			BuildMacro.addAttribute(new QName("Include"), key);
			BuildMacro.addElement("Value").setText("$(" + key + ")");
		}
		return doc;
	}

	public boolean addMarcos(Document xml, Map<String, String> macros) {
		for (Object UserMacrosObject : xml
				.selectNodes("/*[name()='Project']/*[name()='PropertyGroup'][@Label='UserMacros']")) {
			Element UserMacros = (Element) UserMacrosObject;
			for (Object macroObject : UserMacros.selectNodes("*")) {
				Node macro = (Node) macroObject;
				String key = macro.getName();
				String value = macros.remove(key);
				if (!macro.getText().equals(value)) {
					macro.setText(value);
				}
			}
			if (macros.isEmpty())
				return false;
			for (Map.Entry<String, String> e : macros.entrySet()) {
				UserMacros.addElement(e.getKey()).setText(e.getValue());
			}
		}

		for (Object ItemGroupObject : xml.selectNodes("/*[name()='Project']/*[name()='ItemGroup']")) {
			Element ItemGroup = (Element) ItemGroupObject;
			for (String key : macros.keySet()) {
				Element BuildMacro = ItemGroup.addElement("BuilMacro");
				BuildMacro.addAttribute(new QName("Include"), key);
				BuildMacro.addElement("Value").setText("$(" + key + ")");
			}
		}
		return true;
	}

	private boolean modifySln(File sln) {
		// File folder = sln.getParentFile();
		if (!slns.containsKey(sln.getPath())) {
			ArrayList<String> mvcprojs = getVcprojs(sln);
			slns.put(sln.getPath(), mvcprojs);
			for (String vcprojPath : mvcprojs) {
				String path = sln.getParent() + "\\" + vcprojPath;
				if (vcprojs.contains(path))
					continue;
				vcprojs.add(path);
				File vcproj = new File(path);
				// String name=vcproj.getName();
				// File folder=new File(vcproj.getParent()+"/.bak");
				// for(File vcprojBak:folder.listFiles()){
				// if(vcprojBak.getName().matches(name+".*")){
				// vcprojBak.renameTo(vcproj);
				// }
				// }
				modifyVcproj(vcproj);
			}
			return true;
		}
		return false;
	}

	private static SAXReader DEFAULT_SAXREADER = null;

	private boolean modifyVcproj(File vcproj) {
		String path = vcproj.getParent().replaceAll("\\\\", "/");
		// System.out.println(path);
		String macroPath = userMacros.get(path);
		if (macroPath == null) {
			HashMap<String, String> macro = new HashMap<>();
			macro.put("ProjectRoot", path);
			macro.put("CygwinHome", CygwinHome);
			macro.put("CygwinMakeDir", CygwinMakeDir);
			macro.put("TotalRoot", TotalRoot);
			macro.put("BuildDir", BuildDir);
			macro.put("NMakeOutput", BuildDir + "instdir/program/soffice.bin");
			macro.put("JAVA_HOME_x86", JAVA_HOME_x86);

			try {
				macroPath = path + "/" + USER_MARCO_FILE;
				File macroFile = new File(macroPath);
				macroFile.delete();
				Document doc;
				boolean needSave = true;
				if (!macroFile.exists()) {
					doc = createUserMacros(macro);
				} else {
					if (DEFAULT_SAXREADER == null)
						DEFAULT_SAXREADER = new SAXReader();
					doc = DEFAULT_SAXREADER.read(macroFile);
					needSave = addMarcos(doc, macro);
				}
				if (needSave) {
					FileOutputStream out = new FileOutputStream(macroPath);
					saveXml(out, doc);
					out.close();
				}
				userMacros.put(path, macroPath);
			} catch (IOException | DocumentException e) {
				e.printStackTrace();
				return false;
			}
		}
		Document vcprojDoc;
		try {
			if (DEFAULT_SAXREADER == null)
				DEFAULT_SAXREADER = new SAXReader();
			vcprojDoc = DEFAULT_SAXREADER.read(vcproj);
		} catch (DocumentException e) {
			e.printStackTrace();
			return false;
		}
		// Start to edit *.vcxproj file
		vcprojAddMacro(vcprojDoc, USER_MARCO_FILE);
		String relativePath = path.replaceFirst(TotalRoot, "");
		String oldProjectRoot = modifyVcprojPath(vcprojDoc, relativePath);

		if (oldProjectRoot != null) {
			File filterFile = new File(vcproj.getPath() + ".filters");
			Document filter;
			try {
				if (DEFAULT_SAXREADER == null)
					DEFAULT_SAXREADER = new SAXReader();
				filter = DEFAULT_SAXREADER.read(filterFile);
			} catch (DocumentException e) {
				e.printStackTrace();
				return false;
			}
			modifyFilter(filter, oldProjectRoot, relativePath);
			try {
				// OutputStream out = System.out;
				OutputStream out = new FileOutputStream(filterFile);
				saveXml(out, filter);
				out.close();
				// System.exit(0);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (showDoc)
			try {
				// OutputStream out = System.out;
				FileOutputStream out = null;
				if (out == null || !out.equals(System.out)) {
					File back = new File(
							path + "/.bak/" + vcproj.getName() + "_" + System.currentTimeMillis() + ".bak");
					back.createNewFile();
					// System.out.println(back.getPath());
					File bakFolder = new File(path + "/.bak");
					if (!bakFolder.exists()) {
						bakFolder.mkdir();
					} else {
						if (bakFolder.isFile()) {
							bakFolder.delete();
							bakFolder.mkdir();
						} else {
							// for(File f:bakFolder.listFiles()){
							// f.delete();
							// }
						}
						// return false;
					}
					FileInputStream tmpIn = new FileInputStream(vcproj);
					FileOutputStream tmpOut = new FileOutputStream(back);
					byte[] buff = new byte[1024 * 1024];
					int len;
					while ((len = tmpIn.read(buff)) != -1) {
						tmpOut.write(buff, 0, len);
					}
					tmpIn.close();
					tmpOut.close();
				}
				out = new FileOutputStream(vcproj);
				try {
					saveXml(out, vcprojDoc);
				} catch (StackOverflowError e) {
					System.out.println(vcproj.getPath());
				}
				if (!out.equals(System.out))
					out.close();
				// System.exit(0);
			} catch (IOException e) {
				e.printStackTrace();
			}
		// else
		// System.exit(0);
		return true;
	}

	boolean showDoc = true;

	private void vcprojAddMacro(Document vcproj, String macroFile) {
		ArrayList<String> conditions = new ArrayList<>();
		Element Project = vcproj.getRootElement();
		for (Object configObject : vcproj
				.selectNodes("/*[name()='Project']/*[name()='PropertyGroup'][@Label='Configuration']")) {
			Element config = (Element) configObject;
			conditions.add(config.attributeValue("Condition"));
		}
		Element ExtensionSettings = (Element) vcproj.selectSingleNode(
				"/*[name()='Project']/*[name()='Import'][@Project='$(VCTargetsPath)\\Microsoft.Cpp.props']");
		// System.out.println(ExtensionSettings);
		// int index = vcproj.indexOf(ExtensionSettings);

		@SuppressWarnings("unchecked")
		List<Element> list = (List<Element>) (Project.selectNodes("*"));
		int index = list.indexOf(ExtensionSettings);
		// System.out.println(index);
		for (String condition : conditions) {
			Element importGroup = (Element) Project.selectSingleNode(
					"*[name()='ImportGroup'][@Label='PropertySheets'][@Condition=\"" + condition + "\"]");
			if (importGroup == null) {
				importGroup = Project.addElement("ImportGroup");
				importGroup.addAttribute(new QName("Label"), "PropertySheets");
				importGroup.addAttribute(new QName("Condition"), condition);
				list.add(++index, importGroup);
			}
			Element Import = (Element) importGroup.selectSingleNode("*[name()='Import'][@Project='" + macroFile + "']");
			if (Import == null)
				importGroup.addElement("Import").addAttribute(new QName("Project"), macroFile);
		}
		Project.setContent(list);
	}

	private static String REG_DUILDDIR_PATTERN = "\"([A-Z]+=\\\\\"[a-zA-Z0-9$/:]+\\\\\";)*BUILDDIR=\\\\\"("
			+ NIX_PATH_PATTERN + ")\\\\\"(;SRC_ROOT=\\\\\"(" + NIX_PATH_PATTERN + ")\\\\\")?";
	private static Pattern BUILDDIR_PATTERN = Pattern.compile(REG_DUILDDIR_PATTERN);

	private static final String[] SDK_MARCO_LIST = { "$(VC_VC_IncludePath)", "$(UniversalCRT_IncludePath)",
			"$(WindowsSdkDir)include", "$(WindowsSDK_IncludePath)", "$(JAVA_HOME_x86)include",
			"$(JAVA_HOME_x86)include\\win32" };
	private static final String[] BUF_SDK_MARCO_LIST = new String[SDK_MARCO_LIST.length + 1];

	private String modifyVcprojPath(Document vcproj, String relativePath) {
		// System.out.println(REG_DUILDDIR_PATTERN);
		String oldProjectRoot = "";
		String oldBuildDir = "";
		for (Object NMakeBuildCommandLineObject : vcproj
				.selectNodes("/*[name()='Project']/*[name()='PropertyGroup']/*[name()='NMakeBuildCommandLine']")) {
			Element commandLine = (Element) NMakeBuildCommandLineObject;
			Element Group = commandLine.getParent();
			for (Object commandLineObject : Group
					.selectNodes("*[starts-with(name(),'NMake')][ends-with(name(),'CommandLine')]")) {
				commandLine = (Element) commandLineObject;
				// System.out.println(commandLine.getText());
				String[] command = commandLine.getText().split("\\s+");
				command[0] = "$(CygwinHome)bin/dash.exe";
				String tmp = command[2];
				// System.out.println(tmp);
				Matcher matcher = BUILDDIR_PATTERN.matcher(tmp);
				// System.out.println("123");
				// System.out.println(tmp);
				// System.out.println("123");
				matcher.find();
				// System.out.println(matcher.group(1));
				String oldSrcRoot = null;
				try {
					oldBuildDir = matcher.group(2);
					oldSrcRoot = matcher.group(4);
				} catch (IllegalStateException e) {
					e.printStackTrace();
					System.out.println(tmp);
					System.exit(0);
				}
				tmp = tmp.replaceAll(oldBuildDir, "\\$(BuildDir)");
				if (oldSrcRoot != null) {
					tmp = tmp.replaceAll(oldSrcRoot, "\\$(TotalRoot)");
				} else {
					tmp = tmp + ";SRC_ROOT=\\\"$(TotalRoot)\\\"";
				}
				command[2] = tmp;

				command[3] = "$(CygwinMakeDir)";
				oldProjectRoot = command[5];
				command[5] = "$(ProjectRoot)";
				String text = String.join(" ", command);
				commandLine.setText(text);
				// System.out.println(text);
				// System.exit(0);
			}
			Element NMakeOutput = (Element) Group.selectSingleNode("*[name()='NMakeOutput']");
			NMakeOutput.setText("$(NMakeOutput)");
			Element IncludePath = (Element) Group.selectSingleNode("*[name()='IncludePath']");
			String text = IncludePath.getText();
			oldBuildDir = oldBuildDir + "/";
			oldProjectRoot = oldProjectRoot + "/";
			// System.out.println(oldBuildDir);
			// System.out.println(oldProjectRoot);
			text = text.replaceAll(oldBuildDir, "\\$(BuildDir)");
			text = text.replaceAll(oldProjectRoot, "\\$(ProjectRoot)");
			text = text.replaceAll(oldBuildDir.replaceAll("/", "\\\\\\\\"), "\\$(BuildDir)");
			text = text.replaceAll(oldProjectRoot.replaceAll("/", "\\\\\\\\"), "\\$(ProjectDir)");
			// System.out.println(oldProjectRoot);
			String oldTotalRoot;
			if (oldProjectRoot.matches("[A-Za-z]:.*"))
				oldTotalRoot = oldProjectRoot.substring(0, oldProjectRoot.length() - relativePath.length() - 1);
			else {
				oldTotalRoot = "\\$(ProjectDir)..\\\\Build\\\\";
			}
			// System.out.println(relativePath);
			// System.out.println(oldTotalRoot);
			// System.exit(0);
			try {
				text = text.replaceAll(oldTotalRoot, "\\$(TotalRoot)");
				text = text.replaceAll(oldTotalRoot.replaceAll("/", "\\\\\\\\"), "\\$(TotalRoot)");
			} catch (PatternSyntaxException e) {
				System.out.println(oldTotalRoot);
				System.out.println(oldProjectRoot);
				e.printStackTrace();
			}
			String[] paths = text.split(";");
			String newText = "";
			boolean start = false;
			for (String path : paths) {
				if (notSdkPath(path)) {
					if (Arrays.binarySearch(SDK_MARCO_LIST, path) < 0)
						if (!start) {
							start = true;
							newText = path;
						} else {
							newText = String.join(";", newText, path);
						}
				}
			}
			BUF_SDK_MARCO_LIST[0] = newText;
			newText = String.join(";", BUF_SDK_MARCO_LIST);
			IncludePath.setText(newText);
		}
		// System.out.println(oldProjectRoot);
		for (Object ClCompileObjcet : vcproj.selectNodes("//*[name()='ClCompile']")) {
			Element ClCompile = (Element) ClCompileObjcet;
			String include = ClCompile.attributeValue("Include");
			// System.out.println(include);
			if (include.matches("[A-Za-z]:.*")) {
				// System.out.println(include);
				include = include.replaceAll(oldProjectRoot, "");
				if (include.startsWith("/"))
					include = include.substring(1, include.length() - 1);
			}
			ClCompile.addAttribute(new QName("Include"), include);
		}
		for (Object ClIncludeObject : vcproj.selectNodes("//*[name()='ClInclude']")) {
			Element ClInclude = (Element) ClIncludeObject;
			String include = ClInclude.attributeValue("Include");
			// System.out.println(include);
			if (include.matches("[A-Za-z]:.*")) {
				// System.out.println(include);
				include = include.replaceAll(oldProjectRoot, "");
				if (include.startsWith("/"))
					include = include.substring(1, include.length() - 1);
			}
			ClInclude.addAttribute(new QName("Include"), include);
		}
		return oldProjectRoot;
	}

	private boolean notSdkPath(String path) {
		if (path.contains("Microsoft Visual Studio"))
			return false;
		if (path.contains("Windows Kits"))
			return false;
		if (path.contains("\\Java\\jdk"))
			return false;
		return true;
	}

	private void modifyFilter(Document filter, String oldProjectRoot, String relativePath) {
		for (Object ClCompileObjcet : filter.selectNodes("//*[name()='ClCompile']")) {
			Element ClCompile = (Element) ClCompileObjcet;
			String include = ClCompile.attributeValue("Include");
			// System.out.println(include);
			if (include.matches("[A-Za-z]:.*")) {
				// System.out.println(include);
				if (include.startsWith(oldProjectRoot))
					include = include.replaceAll(oldProjectRoot, "");
				else {
					int index = include.indexOf(relativePath);
					include = include.substring(index + relativePath.length(), include.length() - 1);
				}
				if (include.startsWith("/"))
					include = include.substring(1, include.length() - 1);
			}
			ClCompile.addAttribute(new QName("Include"), include);
		}
		for (Object ClIncludeObject : filter.selectNodes("//*[name()='ClInclude']")) {
			Element ClInclude = (Element) ClIncludeObject;
			String include = ClInclude.attributeValue("Include");
			// System.out.println(include);
			if (include.matches("[A-Za-z]:.*")) {
				// System.out.println(include);
				if (include.startsWith(oldProjectRoot))
					include = include.replaceAll(oldProjectRoot, "");
				else {
					int index = include.indexOf(relativePath);
					include = include.substring(index + relativePath.length(), include.length() - 1);
				}
				if (include.startsWith("/"))
					include = include.substring(1, include.length() - 1);
			}
			ClInclude.addAttribute(new QName("Include"), include);
		}
	}

	private static String REG_PROJ_PATTERN = "Project\\(\"\\{" + UUID_PATTERN + "\\}\"\\)\\s=\\s\"" + NAME_PATTERN
			+ "\",\\s\"(" + VCXPROJ_PATTERN + ")\",\\s\"\\{" + UUID_PATTERN + "\\}\"";

	private static Pattern PROJ_PATTERN = Pattern.compile(REG_PROJ_PATTERN);

	private ArrayList<String> getVcprojs(File sln) {
		ArrayList<String> vcprojs = new ArrayList<>();
		// System.out.println(sln.length());
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(sln));
			reader.lines().forEach((line) -> {
				Matcher matcher = PROJ_PATTERN.matcher(line);
				while (matcher.find()) {
					String path = matcher.group(2);
					// System.out.println(path);
					vcprojs.add(path);
				}
			});
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} finally {
			try {
				if (reader != null)
					reader.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return vcprojs;
	}

	public void saveXml(OutputStream out, Document xml) throws IOException {
		OutputFormat format = new OutputFormat();
		format.setEncoding("utf-8");
		format.setIndent("  ");
		format.setNewlines(true);
		format.setNewLineAfterDeclaration(false);
		ByteArrayOutputStream bufOut = new ByteArrayOutputStream();
		XMLWriter writer = new XMLWriter(bufOut, format);
		writer.write(xml);
		writer.flush();
		ByteArrayInputStream bufInput = new ByteArrayInputStream(bufOut.toByteArray());
		bufOut.close();
		BufferedReader reader = new BufferedReader(new InputStreamReader(bufInput));
		BufferedWriter bufWriter = new BufferedWriter(new OutputStreamWriter(out));
		reader.lines().forEach((line) -> {
			if (!line.matches("\\s*")) {
				try {
					bufWriter.write(line + "\n");
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		bufWriter.flush();
	}

	public static void main(String[] args) {
		// String
		// s="\"PATH=\\\"/bin:$PATH\\\";BUILDDIR=\\\"$(BuildDir)\\\";SRC_ROOT=\\\"/bin:$PATH\\\";";
		// System.out.println(s);
		// Matcher matcher=BUILDDIR_PATTERN.matcher(s);
		// matcher.find();
		// System.out.println(matcher.group(4));
		ModifyVcproj m = new ModifyVcproj("C:/cygwin64/", "C:/cygwin64/opt/lo/bin/make", "F:/PPTPlayer/",
				"F:/PPTPlayer/Build/", "C:\\Program Files (x86)\\Java\\jdk1.8.0_181\\");
		m.modifySln(new File("F:\\PPTPlayer\\LibreOffice.sln"));
		// Map<String, String> macros = new HashMap<>();
		// macros.put("ProjectRoot", "E:/VisualStudio/project/PPTPlayer/sd");
		// Document doc = m.createUserMacros(macros);
		// try {
		// m.saveXml(System.out, doc);
		// } catch (IOException e) {
		// e.printStackTrace();
		// }
	}
}
