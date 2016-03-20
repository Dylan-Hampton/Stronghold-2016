package org.usfirst.frc.team3695.robot.vision;

import java.util.concurrent.CopyOnWriteArrayList;

import org.usfirst.frc.team3695.robot.Logger;

import com.ni.vision.NIVision;
import com.ni.vision.NIVision.ColorMode;
import com.ni.vision.NIVision.DrawMode;
import com.ni.vision.NIVision.Image;
import com.ni.vision.NIVision.Point;
import com.ni.vision.NIVision.Range;
import com.ni.vision.NIVision.Rect;
import com.ni.vision.NIVision.ShapeMode;

import edu.wpi.first.wpilibj.CameraServer;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj.vision.USBCamera;

public class Camera extends Thread implements Runnable {
	private static Camera instance;
	
	/**
	 * The minimum size of the convex hull of the goal.
	 */
	public static final int GOAL_MIN_AREA = 800;
	
	/**
	 * Boolean that represents if the camera should be
	 * controlled with a controller or not.
	 */
	private boolean controllerable = true;
	
	/**
	 * Cameras attached to the robot.
	 */
	private final USBCamera frontCam,
							rearCam;
	
	/**
	 * True if the camera is running. Only one should be true at a time.
	 */
	private boolean frontCamOn = false,
					rearCamOn = false;
	
	/**
	 *  Different images that can be displayed to the camera feed.
	 */
	public static final int NO_CAM = 0,
							FRONT_PROCCESSED = 1,
							FRONT_CAM = 2,
							REAR_CAM = 3;
	
	/**
	 * Variables used to control the camera.
	 */
	private int cameraView = FRONT_CAM,
				newCameraView = FRONT_CAM;
	/**
	 * All of the images that can be shown on camera.
	 */
	private Image frontFrame = NIVision.imaqCreateImage(NIVision.ImageType.IMAGE_RGB, 0),
				  frontProcFrame = NIVision.imaqCreateImage(NIVision.ImageType.IMAGE_U8, 0),
				  rearFrame = NIVision.imaqCreateImage(NIVision.ImageType.IMAGE_RGB, 0),
				  waitFrame = NIVision.imaqCreateImage(NIVision.ImageType.IMAGE_RGB, 0),
				  noFrame = NIVision.imaqCreateImage(NIVision.ImageType.IMAGE_RGB, 0);
	
	/**
	 * The Hue, Saturation, and Value ranges for image recognition.
	 */
	private Range H = CameraConstants.HUE(),
				  S = CameraConstants.SATURATION(),
				  V = CameraConstants.VALUE();
	
	/**
	 * Used for the loading animation.
	 */
	private double startTime = 0.0;
	
	/**
	 * An array list of arrays that contain data about the particles on the robot.
	 */
	private CopyOnWriteArrayList<int[]> output = new CopyOnWriteArrayList<>(); 
	
	/**
	 * List of data that needs to be collected for each particle.
	 */
	private NIVision.MeasurementType[] dataToCollect = new NIVision.MeasurementType[]{
			NIVision.MeasurementType.MT_CENTER_OF_MASS_X,
			NIVision.MeasurementType.MT_CENTER_OF_MASS_Y,
			NIVision.MeasurementType.MT_AREA,
			NIVision.MeasurementType.MT_CONVEX_HULL_AREA,
			NIVision.MeasurementType.MT_BOUNDING_RECT_WIDTH,
			NIVision.MeasurementType.MT_BOUNDING_RECT_HEIGHT};
	
	/**
	 * Creates a new set of cameras. A set of cameras consists of a front and a rear
	 * camera. If no cameras exist, or some have a problem, this constructor will handle
	 * that situation without throwing an exception. In theory, it'll print a "No Camera Feed!"
	 * message to the camera viewer if there is a problem.
	 */
	private Camera() throws Exception {
		//Create the images
		NIVision.imaqSetImageSize(noFrame, 640, 480);
		NIVision.imaqSetImageSize(waitFrame, 640, 480);
		NIVision.imaqSetImageSize(frontProcFrame, 640, 480);
		
		//Create a big red "NO" sign on one of them. This is shown when an exception is thrown.
		NIVision.imaqDrawShapeOnImage(noFrame, noFrame, new Rect(0,(640/2) - (480/2), 480, 480), DrawMode.PAINT_VALUE, ShapeMode.SHAPE_OVAL, getColor(0xFF,0x0,0x0));
		NIVision.imaqDrawShapeOnImage(noFrame, noFrame, new Rect(10,(640/2) - (480/2) + 10, 460, 460), DrawMode.PAINT_VALUE, ShapeMode.SHAPE_OVAL, getColor(0,0,0));
		Point topLeft = new Point((int)((320 - 230 * (Math.sqrt(2)/2))+ 0.5),
								  (int)((240 - 230 * (Math.sqrt(2)/2)) + 0.5));
		Point bottomRight = new Point((int)((320 + 230 * (Math.sqrt(2)/2)) + 0.5),
				  					  (int)((240 + 230 * (Math.sqrt(2)/2)) + 0.5));
		NIVision.imaqDrawLineOnImage(noFrame, noFrame, DrawMode.DRAW_VALUE, topLeft, bottomRight, getColor(0xFF,0x00,0x00));
		for(int i = 1; i <= 5; i++) { 
			NIVision.imaqDrawLineOnImage(noFrame, noFrame, DrawMode.DRAW_VALUE, new Point(topLeft.x, topLeft.y - i), new Point(bottomRight.x + i, bottomRight.y), getColor(0xFF,0x00,0x00));
			NIVision.imaqDrawLineOnImage(noFrame, noFrame, DrawMode.DRAW_VALUE, new Point(topLeft.x - i, topLeft.y), new Point(bottomRight.x, bottomRight.y + i), getColor(0xFF,0x00,0x00));
		}
		
		//Attempt to start the two cameras.
		frontCam = startCam("front camera", CameraConstants.FRONT_CAM_NAME);
		rearCam = startCam("rear camera",CameraConstants.REAR_CAM_NAME);
	}
	
	/**
	 * Gets the one and only instance of this camera thread.
	 * @return The camera thread.
	 */
	public static Camera getInstance() {
		if(instance == null) {
	        try {
	        	instance = new Camera();
	        	instance.start();
	        	return instance;
	        } catch (Exception e) {
	        	Logger.err("There was a camera error! Check the constructor of the camera class.", e);
	        	instance = null;
	        	return instance;
	        }
		} else {
			return instance;
		}
	}

	public void run() {
		//Try to switch the camera.
		boolean launchThread = true;
		try {
			viewCam(FRONT_CAM);
		} catch (Exception e) {
			Logger.err("The main thread exited! ", e); 
			launchThread = false;
		}
		while(launchThread) {
			try {
				long pastTime = System.currentTimeMillis();

				//Switch to which camera we are viewing.
				out: switch(cameraView) {
				case FRONT_PROCCESSED:
					frontCam.getImage(frontFrame);
					NIVision.imaqColorThreshold(frontProcFrame, frontFrame, 0x00FFFFFF, ColorMode.HSV, H, S, V); //Get a black and white image from a Hue, Saturation, and Value
					int numOfParticles = NIVision.imaqCountParticles(frontProcFrame, 1);
					output = new CopyOnWriteArrayList<>();
					for (int particle = 0; particle < numOfParticles; particle++) { //Process the particles
						int[] data = new int[dataToCollect.length];
						for(int i = 0; i < dataToCollect.length; i++) {
							data[i] = (int)(NIVision.imaqMeasureParticle(frontProcFrame, particle, 0, dataToCollect[i]));
						}
						output.add(data);
					}
					NIVision.imaqMask(frontFrame, frontFrame, frontProcFrame); //Mask the image with the color one.
					for(int i = 0; i < output.size(); i++) {
						NIVision.imaqDrawShapeOnImage(frontFrame, frontFrame, new Rect(output.get(i)[1] - 2, output.get(i)[0] - 2, 4, 4), DrawMode.PAINT_VALUE, ShapeMode.SHAPE_RECT, Camera.getColor(0xFF, 0x00, 0x00));
					}
					CameraServer.getInstance().setImage(frontFrame);
					break out;
				case FRONT_CAM:
					frontCam.getImage(frontFrame);
					//NIVision.imaqDrawShapeOnImage(frontFrame, frontFrame, new Rect((480/2) - 220,(640/2) - 50, 100, 100), DrawMode.PAINT_VALUE, ShapeMode.SHAPE_RECT, getColor(0,0,0));
					CameraServer.getInstance().setImage(frontFrame);
					break out;
				case REAR_CAM:
					rearCam.getImage(rearFrame);
					CameraServer.getInstance().setImage(rearFrame);
					break out;
				default:
				case NO_CAM:
					CameraServer.getInstance().setImage(noFrame);
					break out;
				}
				
				long currentTime = System.currentTimeMillis();
				SmartDashboard.putNumber("Camera Thread FPS", 1000.0 / (double)(currentTime - pastTime));
				if(newCameraView != cameraView) {
					viewCam(newCameraView);
				}
			} catch (Exception e) {
				Logger.err("Possibly recoverable error occcured! ", e);
				try {
					CameraServer.getInstance().setImage(noFrame);
					Thread.sleep(2000);	//Wait for error to go away.
					viewCam(FRONT_CAM); //Attempt to restart the front camera. Might fail.
				} catch (Exception e2) {
					CameraServer.getInstance().setImage(noFrame);
					Logger.err("Nope, it was an irrecoverable error :( ", e2); 
					launchThread = false;
					break;
				}
			}
		}
	}
	
	/**
	 * Start the process of switching the camera from one cam to another. Parameters are
	 * Camera.NO_CAM, Camera.FRONT_PROCCESSED, Camera.FRONT_CAM, Camera.REAR_CAM
	 * @param cam An integer of the camera.
	 */
	public synchronized void switchCam(int cam) {
		newCameraView = cam;
	}
	
	/**
	 * This method attempts to switch the camera to a new camera feed.
	 * @param cam Use the constants Camera.NO_CAM, Camera.FRONT_PROCCESSED
	 * Camera.FRONT_CAM or Camera.REAR_CAM to switch the camera to a
	 * different feed. 
	 */
	private void viewCam(int newCameraView) throws Exception {
		CameraServer.getInstance().setQuality(CameraConstants.SERVER_QUALITY());
		Loading load = new Loading(waitFrame, startTime);
		load.start();
		switch(newCameraView) {
		case FRONT_PROCCESSED:
			H = CameraConstants.HUE();
			S = CameraConstants.SATURATION();
			V = CameraConstants.VALUE();
			Logger.out("This: " + H.minValue + ", " + H.maxValue + "; " + S.minValue + ", " + S.maxValue + "; " + V.minValue + ", " + V.maxValue);
			Logger.out("Start proccessed cam...");
			if(rearCam != null && rearCamOn) {
				rearCam.stopCapture();
				rearCam.closeCamera();
				rearCamOn = false;
			}
			if(frontCam != null) {
				frontCam.setWhiteBalanceManual(USBCamera.WhiteBalance.kFixedIndoor);
				frontCam.setBrightness(CameraConstants.FRONT_BRIGHTNESS()); //different brightness.
				frontCam.setFPS(30);
				frontCam.setSize(320, 240);  //lower res for faster processing.
				frontCam.updateSettings();
				frontCam.openCamera();
				frontCam.startCapture();
				frontCam.getImage(frontFrame); //Remove broken image.
				frontCamOn = true;
			}
			cameraView = FRONT_PROCCESSED;
			break;
		case FRONT_CAM:
			Logger.out("Start front cam...");
			if(rearCam != null && rearCamOn) {
				rearCam.stopCapture();
				rearCam.closeCamera();
				rearCamOn = false;
			}
			if(frontCam != null) {
				frontCam.setWhiteBalanceManual(USBCamera.WhiteBalance.kFixedIndoor);
				frontCam.setBrightness(0);
				frontCam.setFPS(30);
				frontCam.setSize(640, 480);
				frontCam.updateSettings();
				frontCam.openCamera();
				frontCam.startCapture();
				frontCam.getImage(frontFrame); //Remove broken image.
				frontCamOn = true;
			}
			cameraView = FRONT_CAM;
			break;
		case REAR_CAM:
			Logger.out("Start rear cam...");
			if(frontCam != null && frontCamOn) {
				frontCam.stopCapture();
				frontCam.closeCamera();
				frontCamOn = false;
			}
			if(rearCam != null) {
				rearCam.setWhiteBalanceManual(USBCamera.WhiteBalance.kFixedIndoor);
				rearCam.setFPS(30);
				rearCam.setSize(640, 480);
				rearCam.updateSettings();
				rearCam.openCamera();
				rearCam.startCapture();
				rearCam.getImage(rearFrame); //Remove broken image.
				rearCamOn = true;
			}
			cameraView = REAR_CAM;
			break;
		default:
		case NO_CAM:
			Logger.out("Start no cam...");
			if(frontCam != null &&frontCamOn) {
				frontCam.stopCapture();
				frontCam.closeCamera();
				frontCamOn = false;
			}
			if(rearCam != null && rearCamOn) {
				rearCam.stopCapture();
				rearCam.closeCamera();
				rearCamOn = false;
			}
			cameraView = NO_CAM;
		}
		Thread.sleep(100); //Give the camera about a tenth of a second to fully switch. This clears the broken images caused by switching from the camera server.
		Logger.out("Stop loading animation...");
		startTime = load.end();
		while(load.running()) {
			Thread.sleep(50);
		}
		load = null; //Dispose the thread.
		Logger.err("Switched cams!");
		cameraView = newCameraView;
	}
	
	/**
	 * Starts a camera.
	 * @param humanName A human readable name for the camera
	 * @param camName The camera string as retrieved by the RoboRIO dash board
	 * @return A USB camera if no exception is thrown. Null otherwise.
	 */
	private USBCamera startCam(String humanName, String camName) {
		USBCamera cam = null;
		try{
			cam = new USBCamera(camName);
		} catch (Exception e) {
			Logger.err("Could not start the " + humanName + " nammed \"" + camName + "\"!", e);
		}
		return cam;
	}
	
	/**
	 * Takes a Red, Green, and Blue value and returns the appropriate float. Maybe
	 * @param r Redness
	 * @param g Greenness
	 * @param b Blueness
	 * @return A float
	 */
	public static float getColor(int r, int g, int b) {
		if(r<0) {r=0;}; if(r>0xFF) {r = 0xFF;}; //Limit range for red
		if(g<0) {g=0;}; if(g>0xFF) {g = 0xFF;}; //Limit range for blue
		if(b<0) {b=0;}; if(b>0xFF) {b = 0xFF;}; //Limit range for green
		return (float)(0x00000000 + (((int)g) << 16) + (((int)b) << 8) + (((int)r)));
	}
	
	/**
	 * Returns if the camera is actually doing image processing.
	 */
	public boolean isProccessingCamera() {
		return cameraView == FRONT_PROCCESSED;
	}
	
	/**
	 * True if the camera can be manually be controlled by a controller.
	 * @return True if the controller should be able to switch the camera
	 * to some other camera, false if it should not.
	 */
	public boolean isControllable() {
		return controllerable;
	}
	
	/**
	 * Set if a controller should be used on the camera.
	 * @param controllerable True for controllers, false for code based controlling.
	 */
	public void controllerable(boolean controllerable) {
		this.controllerable = controllerable;
	}

	/**
	 * Returns the X/Y position of the goal.
	 * @return getGoalXY()[0] x position and getGoalXY()[1] y position.
	 */
	public double[] getGoalXY() {
		int convexArea = -1;
		int x = -1;
		int y = -1;;
		for(int[] data : output) {
			if(data[3/*The convex hull area*/] > GOAL_MIN_AREA) {
				convexArea = data[3];
				if(data[0/*The X position*/] > x) {
					x = data[0];
				}
				if(data[1/*The Y position]*/] > y) {
					y = data[1];
				}
			}
		}
		if(convexArea < 0) {
			return new double[]{-1.0,-1.0};
		} else {
			return new double[]{(double)x,(double)y};
		}
	}
}
