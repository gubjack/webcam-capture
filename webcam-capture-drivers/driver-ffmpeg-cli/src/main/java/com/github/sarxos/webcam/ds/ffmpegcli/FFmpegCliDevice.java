package com.github.sarxos.webcam.ds.ffmpegcli;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bridj.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sarxos.webcam.WebcamDevice;
import com.github.sarxos.webcam.WebcamException;


public class FFmpegCliDevice implements WebcamDevice, WebcamDevice.BufferAccess {

	private static final Logger LOG = LoggerFactory.getLogger(FFmpegCliDevice.class);

	private volatile Process process = null;

	private String path = "";
	private String name = null;
	private Dimension[] resolutions = null;
	private Dimension resolution = null;

	private AtomicBoolean open = new AtomicBoolean(false);
	private AtomicBoolean disposed = new AtomicBoolean(false);

	protected FFmpegCliDevice(String path, File vfile, String resolutions) {
		this(path, vfile.getAbsolutePath(), resolutions);
	}

	protected FFmpegCliDevice(String path, String name, String resolutions) {
		this.path = path;
		this.name = name;
		this.resolutions = readResolutions(resolutions);
	}

	public String toString() {
		return this.getClass().getSimpleName()
				+ "("
					+ path
					+ ", " + name
					+ ", " + Arrays.toString(resolutions)
					+ ", " + process
					+ ", " + resolution
					+ ", " + open
					+ ", " + disposed
				+ ")";
	}

	public void startProcess() throws IOException {
		LOG.debug("buildCommand");
		String[] astrProcess = buildCommand();
		LOG.debug("startProcess() <= " + Arrays.toString(astrProcess));
		ProcessBuilder builder = new ProcessBuilder(astrProcess);
		builder.redirectErrorStream(true); // so we can ignore the error stream

		process = builder.start();
	}

	private byte[] readNextFrame() throws IOException {
		LOG.debug("readNextFrame()");
		InputStream out = process.getInputStream();

		final int SIZE = arraySize();

		int cursor = 0;
		byte[] buffer = new byte[SIZE];

		while (isAlive(process)) {
			int iMissing = SIZE - cursor;
			LOG.trace("readNextFrame() {}", iMissing);

				// If buffer is not full yet
				if (iMissing > 0) {
					int  iRead = out.read(buffer, cursor, iMissing);
					cursor += iRead;
				} else {
					break;
				}
		}

		return buffer;
	}

	/**
	 * Based on answer: https://stackoverflow.com/a/12062505/7030976
	 *
	 * @param bgr - byte array in bgr format
	 * @return new image
	 */
	private BufferedImage buildImage(byte[] bgr) {
		BufferedImage image = new BufferedImage(resolution.width, resolution.height, BufferedImage.TYPE_3BYTE_BGR);
		byte[] imageData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
		System.arraycopy(bgr, 0, imageData, 0, bgr.length);

		return image;
	}

	private static boolean isAlive(Process p) {
		try {
			p.exitValue();
			return false;
		} catch (IllegalThreadStateException e) {
			return true;
		}
	}

	private Dimension[] readResolutions(String res) {
		List<Dimension> resolutions = new ArrayList<Dimension>();
		String[] parts = res.split(" ");

		for (String part : parts) {
			String[] xy = part.split("x");
			resolutions.add(new Dimension(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
		}

		return resolutions.toArray(new Dimension[resolutions.size()]);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Dimension[] getResolutions() {
		return resolutions;
	}

	@Override
	public Dimension getResolution() {
		if (resolution == null) {
			resolution = getResolutions()[0];
		}
		return resolution;
	}

	private String getResolutionString() {
		Dimension d = getResolution();
		return String.format("%dx%d", d.width, d.height);
	}

	@Override
	public void setResolution(Dimension resolution) {
		this.resolution = resolution;
	}

	@Override
	public void open() {
		if (!open.compareAndSet(false, true)) {
			return;
		}

		try {
			startProcess();
		} catch (IOException e) {
			throw new WebcamException(e);
		}
	}

	@Override
	public void close() {
		if (!open.compareAndSet(true, false)) {
			return;
		}

		process.destroy();

		try {
			process.waitFor();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void dispose() {
		if (disposed.compareAndSet(false, true) && open.get()) {
			close();
		}
	}

	@Override
	public boolean isOpen() {
		return open.get();
	}

	public String[] buildCommand() {
		String captureDriver = FFmpegCliDriver.getCaptureDriver();

		String deviceInput = name;
		if (Platform.isWindows()) {
			deviceInput = "\"video=" + name + "\"";
		}

		return new String[] {
			FFmpegCliDriver.getCommand(path),
			"-loglevel", "panic", // suppress ffmpeg headers
			"-f", captureDriver, // camera format
			"-s", getResolutionString(), // frame dimension
			"-framerate", "1",  // desired v4l2 input frame rate in fps
			"-i", deviceInput, // input file
			"-r", "1:2", // output frame rate fraction in fps
			"-vcodec", "rawvideo", // raw output
			"-f", "rawvideo", // raw output
			"-vf", "hflip", // flip image horizontally
			"-vsync", "vfr", // avoid frame duplication
			"-pix_fmt", "bgr24", // output format as bgr24
			"-", // output to stdout
		};
	}

	@Override
	public BufferedImage getImage() {
		if (!open.get()) {
			return null;
		}

		try {
			return buildImage(readNextFrame());
		} catch (IOException e) {
			throw new WebcamException(e);
		}
	}

	@Override
	public ByteBuffer getImageBytes() {

		if (!open.get()) {
			return null;
		}

		final ByteBuffer buffer;
		try {
			buffer = ByteBuffer.allocate(arraySize());
			buffer.put(readNextFrame());
		} catch (IOException e) {
			throw new WebcamException(e);
		}

		return buffer;
	}

	@Override
	public void getImageBytes(ByteBuffer byteBuffer) {
		try {
			byteBuffer.put(readNextFrame());
		} catch (IOException e) {
			throw new WebcamException(e);
		}
	}

	private int arraySize() {
		return resolution.width * resolution.height * 3;
	}
}