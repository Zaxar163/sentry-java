package io.sentry.buffer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.logging.Logger;

import io.sentry.event.Event;

/**
 * Stores {@link Event} objects to a directory on the filesystem and allows them
 * to be flushed to Sentry (and deleted) at a later time.
 */
public class DiskBuffer implements Buffer {

	/**
	 * File suffix added to all serialized event files.
	 */
	public static final String FILE_SUFFIX = ".sentry-event";

	private static final Logger logger = Logger.getLogger(DiskBuffer.class.getName());

	private int maxEvents;
	private final File bufferDir;

	/**
	 * Construct an DiskBuffer which stores errors in the specified directory on
	 * disk.
	 *
	 * @param bufferDir
	 *            File representing directory to store buffered Events in
	 * @param maxEvents
	 *            The maximum number of events to store offline
	 */
	public DiskBuffer(File bufferDir, int maxEvents) {
		super();

		this.bufferDir = bufferDir;
		this.maxEvents = maxEvents;

		String errMsg = "Could not create or write to disk buffer dir: " + bufferDir.getAbsolutePath();
		try {
			bufferDir.mkdirs();
			if (!bufferDir.isDirectory() || !bufferDir.canWrite())
				throw new RuntimeException(errMsg);
		} catch (Exception e) {
			throw new RuntimeException(errMsg, e);
		}

		logger.info(
				Integer.toString(getNumStoredEvents()) + " stored events found in dir: " + bufferDir.getAbsolutePath());
	}

	/**
	 * Store a single event to the add directory. Java serialization is used and
	 * each event is stored in a file named by its UUID.
	 *
	 * @param event
	 *            Event to store in add directory
	 */
	@Override
	public void add(Event event) {
		if (getNumStoredEvents() >= maxEvents) {
			logger.info("Not adding Event because at least " + Integer.toString(maxEvents)
					+ " events are already stored: " + event.getId());
			return;
		}

		File eventFile = new File(bufferDir.getAbsolutePath(), event.getId().toString() + FILE_SUFFIX);
		if (eventFile.exists()) {
			logger.info(
					"Not adding Event to offline storage because it already exists: " + eventFile.getAbsolutePath());
			return;
		} else
			logger.info("Adding Event to offline storage: " + eventFile.getAbsolutePath());

		try (FileOutputStream fileOutputStream = new FileOutputStream(eventFile);
				ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream)) {
			objectOutputStream.writeObject(event);
		} catch (Exception e) {
			logger.finest("Error writing Event to offline storage: " + event.getId() + " " + e);
		}

		logger.info(Integer.toString(getNumStoredEvents()) + " stored events are now in dir: "
				+ bufferDir.getAbsolutePath());
	}

	/**
	 * Deletes a buffered {@link Event} from disk.
	 *
	 * @param event
	 *            Event to delete from the disk.
	 */
	@Override
	public void discard(Event event) {
		File eventFile = new File(bufferDir, event.getId().toString() + FILE_SUFFIX);
		if (eventFile.exists()) {
			logger.info("Discarding Event from offline storage: " + eventFile.getAbsolutePath());
			if (!eventFile.delete())
				logger.fine("Failed to delete Event: " + eventFile.getAbsolutePath());
		}
	}

	/**
	 * Attempts to open and deserialize a single {@link Event} from a {@link File}.
	 *
	 * @param eventFile
	 *            File to deserialize into an Event
	 * @return Event from the File, or null
	 */
	private Event fileToEvent(File eventFile) {
		Object eventObj;

		try (FileInputStream fileInputStream = new FileInputStream(new File(eventFile.getAbsolutePath()));
				ObjectInputStream ois = new ObjectInputStream(fileInputStream)) {
			eventObj = ois.readObject();
		} catch (FileNotFoundException e) {
			// event was deleted while we were iterating the array of files
			return null;
		} catch (Exception e) {
			logger.finest("Error reading Event file: " + eventFile.getAbsolutePath() + " " + e);
			if (!eventFile.delete())
				logger.fine("Failed to delete Event: " + eventFile.getAbsolutePath());
			return null;
		}

		try {
			return (Event) eventObj;
		} catch (Exception e) {
			logger.finest("Error casting Object to Event: " + eventFile.getAbsolutePath() + " " + e);
			if (!eventFile.delete())
				logger.fine("Failed to delete Event: " + eventFile.getAbsolutePath());
			return null;
		}
	}

	/**
	 * Returns an Iterator of Events that are stored on disk <b>at the point in time
	 * this method is called</b>. Note that files may not deserialize correctly, may
	 * be corrupted, or may be missing on disk by the time we attempt to open them -
	 * so some care is taken to only return valid {@link Event}s.
	 *
	 * If Events are written to disk after this Iterator is created they <b>will
	 * not</b> be returned by this Iterator.
	 *
	 * @return Iterator of Events on disk
	 */
	@Override
	public Iterator<Event> getEvents() {
		final Iterator<File> files = Arrays.asList(bufferDir.listFiles()).iterator();

		return new Iterator<Event>() {
			private Event next = getNextEvent(files);

			@Override
			public boolean hasNext() {
				return next != null;
			}

			@Override
			public Event next() {
				Event toReturn = next;
				next = getNextEvent(files);
				return toReturn;
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	/**
	 * Returns the next *valid* {@link Event} found in an Iterator of Files.
	 *
	 * @param files
	 *            Iterator of Files to deserialize
	 * @return The next Event found, or null if there are none
	 */
	private Event getNextEvent(Iterator<File> files) {
		while (files.hasNext()) {
			File file = files.next();

			// only consider files that end with FILE_SUFFIX
			if (!file.getAbsolutePath().endsWith(FILE_SUFFIX))
				continue;

			Event event = fileToEvent(file);
			if (event != null)
				return event;
		}

		return null;
	}

	private int getNumStoredEvents() {
		int count = 0;
		for (File file : bufferDir.listFiles())
			if (file.getAbsolutePath().endsWith(FILE_SUFFIX))
				count += 1;
		return count;
	}
}
