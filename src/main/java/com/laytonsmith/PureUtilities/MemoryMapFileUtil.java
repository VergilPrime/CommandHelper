
package com.laytonsmith.PureUtilities;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Layton
 */
public class MemoryMapFileUtil {
	private static Map<String, MemoryMapFileUtil> instances = new HashMap<String, MemoryMapFileUtil>();
	/**
	 * The minimum delay between FS writes. In milliseconds.
	 */
	private static final int WRITE_DELAY = 250;
	
	public static synchronized MemoryMapFileUtil getInstance(File f, DataGrabber grabber) throws IOException{
		String s = f.getCanonicalPath().intern();
		MemoryMapFileUtil mem;
		if(!instances.containsKey(s)){
			mem = new MemoryMapFileUtil(f, grabber);
			instances.put(s, mem);
		} else {
			mem = instances.get(s);
		}		
		mem.grabber = grabber;
		return mem;
	}
	
	public static interface DataGrabber{
		byte[] getData();
	}
	
	private String file;
	private DataGrabber grabber;
	private boolean modelDirty = false;
	private boolean fileDirty = false;
	private boolean running = false;
	private long lastWrite = 0;
	private ExecutorService service;
	private MemoryMapFileUtil(File file, DataGrabber grabber) throws IOException{
		this.file = file.getCanonicalPath();
		this.grabber = grabber;
	}
	
	private void run(){
		try{
			synchronized(this){
				running = true;
			}
			while(true){
				//We don't want to write out files too frequently, so we want to check when our last write action was,
				//and delay some if it was too recent.
				long lastWriteDelta = System.currentTimeMillis() - lastWrite;
				if(lastWriteDelta < WRITE_DELAY){
					try {
						Thread.sleep(lastWriteDelta);
					} catch (InterruptedException ex) {}
				}
				File temp = null;
				try {
					synchronized(this){
						if(!modelDirty && !fileDirty){
							return;
						}
					}
					temp = File.createTempFile("MemoryMapFile", ".tmp");
					File permanent = new File(file);
					byte [] data; 
					synchronized(this){
						data = grabber.getData();
						fileDirty = true;
					}
					
					FileUtility.write(data, temp, FileUtility.OVERWRITE, true);
					synchronized(this){
						if(FileUtility.move(temp, permanent)){
							//If and only if the file was moved, do we want to clear the dirty flag.
							fileDirty = false;
							modelDirty = false;
							lastWrite = System.currentTimeMillis();
						}
					}
				} catch (IOException ex) {
					Logger.getLogger(MemoryMapFileUtil.class.getName()).log(Level.SEVERE, null, ex);
				} finally {
					if(temp != null){
						temp.delete();
						temp.deleteOnExit();
					}
				}
			}
		} finally{
			synchronized(this){
				running = false;
			}
		}
	}
	
	/**
	 * Marks the data as dirty. This also triggers the writer to start if it isn't already
	 * started. Multiple calls to mark do not necessarily cause the output to be written
	 * multiple times, it simply sets the flag
	 */
	public void mark(){
		synchronized(this){
			modelDirty = fileDirty = true;
			if(!running){
				getService().submit(new Runnable() {

					public void run() {
						MemoryMapFileUtil.this.run();
					}
				});
			}
		}
	}
	
	private synchronized ExecutorService getService(){
		if(service == null){
			service = Executors.newSingleThreadExecutor(new ThreadFactory() {

				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "MemoryMapWriter-" + file);
					t.setPriority(Thread.MIN_PRIORITY);
					return t;
				}
			});
		}
		return service;
	}
}