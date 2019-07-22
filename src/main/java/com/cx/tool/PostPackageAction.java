package com.cx.tool;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Created by: iland
 * Date: 7/18/2019
 */
public class PostPackageAction {

    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException, XPathExpressionException, TransformerException {
        FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("CxEclipsePlugin");
            }
        };
        File srcFile = new File(args[0]).listFiles(filter)[0];

        File tempDir = new File("C:\\tmp\\workspace\\CxEclipsePlugin-Temp");
        String srcArtJar = "C:\\tmp\\workspace\\CxEclipsePlugin-Temp\\artifacts.jar";
        String trgArtJar = "C:\\tmp\\workspace\\CxEclipsePlugin-Temp\\artifacts22.jar";

        extract(srcFile, tempDir);

        JarFile artJar = new JarFile(srcArtJar);
        FileOutputStream fos = new FileOutputStream(trgArtJar);
        final JarOutputStream jos = new JarOutputStream(fos);

        for (Enumeration e = artJar.entries(); e.hasMoreElements(); ) {
            JarEntry jEntry = (JarEntry) e.nextElement();
            if (!jEntry.getName().equalsIgnoreCase("artifacts.xml")) {
                jos.putNextEntry(jEntry);
                InputStream is = artJar.getInputStream(jEntry);
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buf)) > 0) {
                    jos.write(buf, 0, len);
                }
                is.close();
            } else {
                jos.putNextEntry(new ZipEntry("artifacts.xml"));

                InputStream is = artJar.getInputStream(jEntry);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                try {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(is);
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    NodeList propertyNodes = (NodeList) xPath.evaluate("//property[@name='download.md5']", doc, XPathConstants.NODESET);
                    for (int i = 0; i < propertyNodes.getLength(); i++) {
                        Node node = propertyNodes.item(i);
                        Node size = node.getParentNode().getAttributes().getNamedItem("size");
                        int sizeInt = Integer.valueOf(size.getNodeValue());
                        size.setNodeValue(String.valueOf(sizeInt - 1));
                        Node nextSibling = node.getNextSibling();
                        node.getParentNode().removeChild(node);
                        nextSibling.getParentNode().removeChild(nextSibling.getPreviousSibling());
                    }
                    Source xmlSource = new DOMSource(doc);
                    Result outputTarget = new StreamResult(outputStream);
                    TransformerFactory.newInstance().newTransformer().transform(xmlSource, outputTarget);
                    InputStream docIs = new ByteArrayInputStream(outputStream.toByteArray());
                    byte[] buf = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = docIs.read(buf)) > 0) {
                        jos.write(buf, 0, len);
                    }
                    docIs.close();
                } finally {
                    outputStream.close();
                    is.close();
                }
            }
            jos.closeEntry();
        }
        jos.close();
        artJar.close();

        Files.deleteIfExists(Paths.get(srcArtJar));
        new File(trgArtJar).renameTo(new File(srcArtJar));

        String pluginName = srcFile.getName();

        Path srcFilePath = Paths.get(srcFile.getCanonicalPath()
                .substring(0, srcFile.getCanonicalPath().lastIndexOf(".zip")) + "-org.zip");
        srcFile.renameTo(srcFilePath.toFile());

        Path tempDirPath = Paths.get(tempDir.getCanonicalPath()
                .substring(0, tempDir.getCanonicalPath().lastIndexOf("\\") + 1) + pluginName.substring(0, pluginName.lastIndexOf(".zip")));
        tempDir.renameTo(tempDirPath.toFile());

        zipFolder(tempDirPath, Paths.get(tempDirPath.toString() + ".zip"));

        tempDirPath.toFile().delete();
        Files.deleteIfExists(srcFilePath);
    }

    private static void zipFolder(final Path sourceFolderPath, Path zipPath) throws IOException {
        final ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()));
        Files.walkFileTree(sourceFolderPath, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                zos.putNextEntry(new ZipEntry(sourceFolderPath.relativize(file).toString()));
                Files.copy(file, zos);
                zos.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
        zos.close();
    }

    public static void extract(File zipfile, File outdir) {
        try {
            ZipInputStream zin = new ZipInputStream(new FileInputStream(zipfile));
            ZipEntry entry;
            String name, dir;
            while ((entry = zin.getNextEntry()) != null) {
                name = entry.getName();
                if (entry.isDirectory()) {
                    mkdirs(outdir, name);
                    continue;
                }

                dir = dirpart(name);
                if (dir != null)
                    mkdirs(outdir, dir);

                extractFile(zin, outdir, name);
            }
            zin.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void extractFile(ZipInputStream in, File outdir, String name) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(new File(outdir, name)));
        int count = -1;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        out.close();
    }

    private static void mkdirs(File outdir, String path) {
        File d = new File(outdir, path);
        if (!d.exists())
            d.mkdirs();
    }

    private static String dirpart(String name) {
        int s = name.lastIndexOf(File.separatorChar);
        return s == -1 ? null : name.substring(0, s);
    }

}
