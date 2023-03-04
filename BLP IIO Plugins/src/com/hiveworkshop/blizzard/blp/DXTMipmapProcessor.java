package com.hiveworkshop.blizzard.blp;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Consumer;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.hiveworkshop.blizzard.blp.BLPStreamMetadata.SampleType;
import com.hiveworkshop.blizzard.blp.test.DDSLineReader;
import com.hiveworkshop.lang.LocalizedFormatedString;

/**
 * A class that is responsible for processing between mipmap data and indexed
 * color content.
 * <p>
 * During decoding if the mipmap data is of incorrect size then it is resized to
 * fit and a warning is generated. Some poor BLP implementations, such as used
 * by some versions of Warcraft III, do not read and process mipmap data safely
 * so might be able to extract more meaningful visual information from a
 * technically corrupt file.
 * <p>
 * When encoding images the first image ColorModel is used to determine the
 * color map used. Both BLPIndexColorModel and IndexColorModel are supported
 * although IndexColorModel alpha is not. The direct values of the required
 * bands are then used without further processing. Alpha band is always assumed
 * to be the second band and will be rescaled as required. Missing alpha band
 * will be substituted with opaque pixels if required. Any other bands are
 * discarded.
 * 
 * @author Imperial Good
 */
public class DXTMipmapProcessor extends MipmapProcessor {
	private static final int BANDS_COUNT = 4;
	private SampleType sampleType;

	/**
	 * Constructs a MipmapProcessor for indexed color content.
	 * @param alphaBita 
	 * 
	 * @param alphaBits
	 *            the alpha component bits, if any.
	 * @throws IllegalArgumentException
	 *             if alphaBits is not valid.
	 */
	public DXTMipmapProcessor(byte alphaBits, SampleType sampleType) {
		this.sampleType = sampleType;
	}

	@Override
	public byte[] encodeMipmap(BufferedImage img, ImageWriteParam param,
			Consumer<LocalizedFormatedString> handler) throws IOException {
		throw new UnsupportedOperationException("Writing DXT3 not yet supported");
	}

	@Override
	public BufferedImage decodeMipmap(byte[] mmData, ImageReadParam param,
			int width, int height, Consumer<LocalizedFormatedString> handler)
			throws IOException {


		// Calculate and return a Rectangle that identifies the region of the
		// source image that should be read:
		//
		// 1. If param is null, the upper-left corner of the region is (0, 0),
		//	 and the width and height are specified by the width and height
		//	 arguments. In other words, the entire image is read.
		//
		// 2. If param is not null
		//
		//	 2.1 If param.getSourceRegion() returns a non-null Rectangle, the
		//		  region is calculated as the intersection of param's Rectangle
		//		  and the earlier (0, 0, width, height Rectangle).
		//
		//	 2.2 param.getSubsamplingXOffset() is added to the region's x
		//		  coordinate and subtracted from its width.
		//
		//	 2.3 param.getSubsamplingYOffset() is added to the region's y
		//		  coordinate and subtracted from its height.

		Rectangle sourceRegion = getSourceRegion(param, width, height);

		// Source subsampling is used to return a scaled-down source image.
		// Default 1 values for X and Y subsampling indicate that a non-scaled
		// source image will be returned.

		int sourceXSubsampling = 1;
		int sourceYSubsampling = 1;

		// The final step in reading an image from a source to a destination is
		// to map the source samples in various source bands to destination
		// samples in various destination bands. This lets you return only the
		// red component of an image, for example. Default null values indicate
		// that all source and destination bands are used.

		int[] sourceBands = null;
		int[] destinationBands = null;

		// The destination offset determines the starting location in the
		// destination where decoded pixels are placed. Default (0, 0) values
		// indicate the upper-left corner.

		Point destinationOffset = new Point(0, 0);

		// If param is not null, override the source subsampling, source bands,
		// destination bands, and destination offset defaults.

		if (param != null) {
			sourceXSubsampling = param.getSourceXSubsampling();
			sourceYSubsampling = param.getSourceYSubsampling();
			sourceBands = param.getSourceBands();
			destinationBands = param.getDestinationBands();
			destinationOffset = param.getDestinationOffset();
		}

		// Obtain a BufferedImage into which decoded pixels will be placed. This
		// destination will be returned to the application.
		//
		// 1. If param is not null
		//
		//	 1.1 If param.getDestination() returns a BufferedImage 
		//
		//		  1.1.1 Return this BufferedImage
		//
		//		  Else
		//
		//		  1.1.2 Invoke param.getDestinationType ().
		//
		//		  1.1.3 If the returned ImageTypeSpecifier equals 
		//				  getImageTypes (0) (see below), return its BufferedImage.
		//
		// 2. If param is null or a BufferedImage has not been obtained
		//
		//	 2.1 Return getImageTypes (0)'s BufferedImage.

		BufferedImage dst = getDestination(param, getSupportedImageTypes(0,0), width, height);

		//dst.
		// Verify that the number of source bands and destination bands, as
		// specified by param, are the same. If param is null, 3 is compared
		// with dst.getSampleModel ().getNumBands (), thewhich must also equal 3.
		// An IllegalArgumentException is thrown if the number of source bands
		// differs from the number of destination bands.

		checkReadParamBandSettings(param, BANDS_COUNT, dst.getSampleModel().getNumBands());

		// Create a WritableRaster for the source.

		WritableRaster wrSrc = Raster.createBandedRaster(DataBuffer.TYPE_BYTE, width, 1, BANDS_COUNT, new Point(0, 0));

		byte[][] banks = ((DataBufferByte) wrSrc.getDataBuffer()).getBankData();

		// Create a WritableRaster for the destination.

		WritableRaster wrDst = dst.getRaster();

		// Identify destination rectangle for clipping purposes. Only source
		// pixels within this rectangle are copied to the destination.

		int dstMinX = wrDst.getMinX();
		int dstMaxX = dstMinX + wrDst.getWidth() - 1;
		int dstMinY = wrDst.getMinY();
		int dstMaxY = dstMinY + wrDst.getHeight() - 1;

		// Create a child raster that exposes only the desired source bands.

		if (sourceBands != null) {
			wrSrc = wrSrc.createWritableChild(0, 0, width, 1, 0, 0, sourceBands);
		}

		// Create a child raster that exposes only the desired destination
		// bands.

		if (destinationBands != null) {
			wrDst = wrDst.createWritableChild(0, 0, wrDst.getWidth(), wrDst.getHeight(), 0, 0, destinationBands);
		}

		//Read all bytes for the selected imageIndex (More Memory, Less Time)

		DDSLineReader ddsLineReader = new DDSLineReader();
		
		int srcY = 0;
		try {
			int[] pixel = wrSrc.getPixel(0, 0, (int[]) null);

			for (srcY = 0; srcY < height; srcY++) {
				// Decode the next row from the DDS file.
				// TODO Can be moved down past the continue/breaks...
				ddsLineReader.decodeLine(mmData, sampleType, banks, width, srcY);

				// Reject rows that lie outside the source region, or which are
				// not part of the subsampling.

				if ((srcY < sourceRegion.y)
						|| (srcY >= sourceRegion.y + sourceRegion.height)
						|| (((srcY - sourceRegion.y) % sourceYSubsampling) != 0)) {
					continue;
				}

				// Determine the row's location in the destination.

				int dstY = destinationOffset.y + (srcY - sourceRegion.y) / sourceYSubsampling;
				if (dstY < dstMinY) {
					continue; // The row is above the top of the destination rectangle.
				}

				if (dstY > dstMaxY) {
					break; // The row is below the bottom of the destination rectangle.
				}

				// Copy each subsampled source pixel that fits into the
				// destination rectangle into the destination.

				for (int srcX = sourceRegion.x; srcX < sourceRegion.x + sourceRegion.width; srcX++) {
					if (((srcX - sourceRegion.x) % sourceXSubsampling) != 0) {
						continue;
					}

					int dstX = destinationOffset.x + (srcX - sourceRegion.x) / sourceXSubsampling;
					if (dstX < dstMinX) {
						continue; // The pixel is to the destination rectangle's left.
					}

					if (dstX > dstMaxX) {
						break; // The pixel is to the destination rectangle's right.
					}

					// Copy the pixel. Sub-banding is automatically handled.

					wrSrc.getPixel(srcX, 0, pixel);
					wrDst.setPixel(dstX, dstY, pixel);
				}
			}
		} catch (IOException e) {
			throw new IIOException("Error reading line " + srcY + ": " + e.getMessage(), e);
		}
		return dst;
	}


	@Override
	public Iterator<ImageTypeSpecifier> getSupportedImageTypes(int width,
			int height) {
		return Arrays.asList(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_4BYTE_ABGR)).iterator();
	}

	@Override
	public void readObject(ImageInputStream src,
			Consumer<LocalizedFormatedString> warning) throws IOException {
		canDecode = true;
	}

	@Override
	public void writeObject(ImageOutputStream dst) throws IOException {
		throw new UnsupportedOperationException("Writing DXT3 not yet supported");
	}

    /**
     * A utility method that may be used by readers to compute the
     * region of the source image that should be read, taking into
     * account any source region and subsampling offset settings in
     * the supplied {@code ImageReadParam}.  The actual
     * subsampling factors, destination size, and destination offset
     * are <em>not</em> taken into consideration, thus further
     * clipping must take place.  The {@link #computeRegions computeRegions}
     * method performs all necessary clipping.
     *
     * @param param the {@code ImageReadParam} being used, or
     * {@code null}.
     * @param srcWidth the width of the source image.
     * @param srcHeight the height of the source image.
     *
     * @return the source region as a {@code Rectangle}.
     */
    protected static Rectangle getSourceRegion(ImageReadParam param,
                                               int srcWidth,
                                               int srcHeight) {
        Rectangle sourceRegion = new Rectangle(0, 0, srcWidth, srcHeight);
        if (param != null) {
            Rectangle region = param.getSourceRegion();
            if (region != null) {
                sourceRegion = sourceRegion.intersection(region);
            }

            int subsampleXOffset = param.getSubsamplingXOffset();
            int subsampleYOffset = param.getSubsamplingYOffset();
            sourceRegion.x += subsampleXOffset;
            sourceRegion.y += subsampleYOffset;
            sourceRegion.width -= subsampleXOffset;
            sourceRegion.height -= subsampleYOffset;
        }

        return sourceRegion;
    }

    /**
     * Returns the {@code BufferedImage} to which decoded pixel
     * data should be written.  The image is determined by inspecting
     * the supplied {@code ImageReadParam} if it is
     * non-{@code null}; if its {@code getDestination}
     * method returns a non-{@code null} value, that image is
     * simply returned.  Otherwise,
     * {@code param.getDestinationType} method is called to
     * determine if a particular image type has been specified.  If
     * so, the returned {@code ImageTypeSpecifier} is used after
     * checking that it is equal to one of those included in
     * {@code imageTypes}.
     *
     * <p> If {@code param} is {@code null} or the above
     * steps have not yielded an image or an
     * {@code ImageTypeSpecifier}, the first value obtained from
     * the {@code imageTypes} parameter is used.  Typically, the
     * caller will set {@code imageTypes} to the value of
     * {@code getImageTypes(imageIndex)}.
     *
     * <p> Next, the dimensions of the image are determined by a call
     * to {@code computeRegions}.  The actual width and height of
     * the image being decoded are passed in as the {@code width}
     * and {@code height} parameters.
     *
     * @param param an {@code ImageReadParam} to be used to get
     * the destination image or image type, or {@code null}.
     * @param imageTypes an {@code Iterator} of
     * {@code ImageTypeSpecifier}s indicating the legal image
     * types, with the default first.
     * @param width the true width of the image or tile being decoded.
     * @param height the true width of the image or tile being decoded.
     *
     * @return the {@code BufferedImage} to which decoded pixel
     * data should be written.
     *
     * @exception IIOException if the {@code ImageTypeSpecifier}
     * specified by {@code param} does not match any of the legal
     * ones from {@code imageTypes}.
     * @exception IllegalArgumentException if {@code imageTypes}
     * is {@code null} or empty, or if an object not of type
     * {@code ImageTypeSpecifier} is retrieved from it.
     * @exception IllegalArgumentException if the resulting image would
     * have a width or height less than 1.
     * @exception IllegalArgumentException if the product of
     * {@code width} and {@code height} is greater than
     * {@code Integer.MAX_VALUE}.
     */
    protected static BufferedImage
        getDestination(ImageReadParam param,
                       Iterator<ImageTypeSpecifier> imageTypes,
                       int width, int height)
        throws IIOException {
        if (imageTypes == null || !imageTypes.hasNext()) {
            throw new IllegalArgumentException("imageTypes null or empty!");
        }
        if ((long)width*height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException
                ("width*height > Integer.MAX_VALUE!");
        }

        BufferedImage dest = null;
        ImageTypeSpecifier imageType = null;

        // If param is non-null, use it
        if (param != null) {
            // Try to get the image itself
            dest = param.getDestination();
            if (dest != null) {
                return dest;
            }

            // No image, get the image type
            imageType = param.getDestinationType();
        }

        // No info from param, use fallback image type
        if (imageType == null) {
            Object o = imageTypes.next();
            if (!(o instanceof ImageTypeSpecifier)) {
                throw new IllegalArgumentException
                    ("Non-ImageTypeSpecifier retrieved from imageTypes!");
            }
            imageType = (ImageTypeSpecifier)o;
        } else {
            boolean foundIt = false;
            while (imageTypes.hasNext()) {
                ImageTypeSpecifier type =
                    imageTypes.next();
                if (type.equals(imageType)) {
                    foundIt = true;
                    break;
                }
            }

            if (!foundIt) {
                throw new IIOException
                    ("Destination type from ImageReadParam does not match!");
            }
        }

        Rectangle srcRegion = new Rectangle(0,0,0,0);
        Rectangle destRegion = new Rectangle(0,0,0,0);
        computeRegions(param,
                       width,
                       height,
                       null,
                       srcRegion,
                       destRegion);

        int destWidth = destRegion.x + destRegion.width;
        int destHeight = destRegion.y + destRegion.height;
        // Create a new image based on the type specifier
        return imageType.createBufferedImage(destWidth, destHeight);
    }

    /**
     * Computes the source region of interest and the destination
     * region of interest, taking the width and height of the source
     * image, an optional destination image, and an optional
     * {@code ImageReadParam} into account.  The source region
     * begins with the entire source image.  Then that is clipped to
     * the source region specified in the {@code ImageReadParam},
     * if one is specified.
     *
     * <p> If either of the destination offsets are negative, the
     * source region is clipped so that its top left will coincide
     * with the top left of the destination image, taking subsampling
     * into account.  Then the result is clipped to the destination
     * image on the right and bottom, if one is specified, taking
     * subsampling and destination offsets into account.
     *
     * <p> Similarly, the destination region begins with the source
     * image, is translated to the destination offset given in the
     * {@code ImageReadParam} if there is one, and finally is
     * clipped to the destination image, if there is one.
     *
     * <p> If either the source or destination regions end up having a
     * width or height of 0, an {@code IllegalArgumentException}
     * is thrown.
     *
     * <p> The {@link #getSourceRegion getSourceRegion>}
     * method may be used if only source clipping is desired.
     *
     * @param param an {@code ImageReadParam}, or {@code null}.
     * @param srcWidth the width of the source image.
     * @param srcHeight the height of the source image.
     * @param image a {@code BufferedImage} that will be the
     * destination image, or {@code null}.
     * @param srcRegion a {@code Rectangle} that will be filled with
     * the source region of interest.
     * @param destRegion a {@code Rectangle} that will be filled with
     * the destination region of interest.
     * @exception IllegalArgumentException if {@code srcRegion}
     * is {@code null}.
     * @exception IllegalArgumentException if {@code dstRegion}
     * is {@code null}.
     * @exception IllegalArgumentException if the resulting source or
     * destination region is empty.
     */
    protected static void computeRegions(ImageReadParam param,
                                         int srcWidth,
                                         int srcHeight,
                                         BufferedImage image,
                                         Rectangle srcRegion,
                                         Rectangle destRegion) {
        if (srcRegion == null) {
            throw new IllegalArgumentException("srcRegion == null!");
        }
        if (destRegion == null) {
            throw new IllegalArgumentException("destRegion == null!");
        }

        // Start with the entire source image
        srcRegion.setBounds(0, 0, srcWidth, srcHeight);

        // Destination also starts with source image, as that is the
        // maximum extent if there is no subsampling
        destRegion.setBounds(0, 0, srcWidth, srcHeight);

        // Clip that to the param region, if there is one
        int periodX = 1;
        int periodY = 1;
        int gridX = 0;
        int gridY = 0;
        if (param != null) {
            Rectangle paramSrcRegion = param.getSourceRegion();
            if (paramSrcRegion != null) {
                srcRegion.setBounds(srcRegion.intersection(paramSrcRegion));
            }
            periodX = param.getSourceXSubsampling();
            periodY = param.getSourceYSubsampling();
            gridX = param.getSubsamplingXOffset();
            gridY = param.getSubsamplingYOffset();
            srcRegion.translate(gridX, gridY);
            srcRegion.width -= gridX;
            srcRegion.height -= gridY;
            destRegion.setLocation(param.getDestinationOffset());
        }

        // Now clip any negative destination offsets, i.e. clip
        // to the top and left of the destination image
        if (destRegion.x < 0) {
            int delta = -destRegion.x*periodX;
            srcRegion.x += delta;
            srcRegion.width -= delta;
            destRegion.x = 0;
        }
        if (destRegion.y < 0) {
            int delta = -destRegion.y*periodY;
            srcRegion.y += delta;
            srcRegion.height -= delta;
            destRegion.y = 0;
        }

        // Now clip the destination Region to the subsampled width and height
        int subsampledWidth = (srcRegion.width + periodX - 1)/periodX;
        int subsampledHeight = (srcRegion.height + periodY - 1)/periodY;
        destRegion.width = subsampledWidth;
        destRegion.height = subsampledHeight;

        // Now clip that to right and bottom of the destination image,
        // if there is one, taking subsampling into account
        if (image != null) {
            Rectangle destImageRect = new Rectangle(0, 0,
                                                    image.getWidth(),
                                                    image.getHeight());
            destRegion.setBounds(destRegion.intersection(destImageRect));
            if (destRegion.isEmpty()) {
                throw new IllegalArgumentException
                    ("Empty destination region!");
            }

            int deltaX = destRegion.x + subsampledWidth - image.getWidth();
            if (deltaX > 0) {
                srcRegion.width -= deltaX*periodX;
            }
            int deltaY =  destRegion.y + subsampledHeight - image.getHeight();
            if (deltaY > 0) {
                srcRegion.height -= deltaY*periodY;
            }
        }
        if (srcRegion.isEmpty() || destRegion.isEmpty()) {
            throw new IllegalArgumentException("Empty region!");
        }
    }

    /**
     * A utility method that may be used by readers to test the
     * validity of the source and destination band settings of an
     * {@code ImageReadParam}.  This method may be called as soon
     * as the reader knows both the number of bands of the source
     * image as it exists in the input stream, and the number of bands
     * of the destination image that being written.
     *
     * <p> The method retrieves the source and destination band
     * setting arrays from param using the {@code getSourceBands}
     * and {@code getDestinationBands} methods (or considers them
     * to be {@code null} if {@code param} is
     * {@code null}).  If the source band setting array is
     * {@code null}, it is considered to be equal to the array
     * {@code { 0, 1, ..., numSrcBands - 1 }}, and similarly for
     * the destination band setting array.
     *
     * <p> The method then tests that both arrays are equal in length,
     * and that neither array contains a value larger than the largest
     * available band index.
     *
     * <p> Any failure results in an
     * {@code IllegalArgumentException} being thrown; success
     * results in the method returning silently.
     *
     * @param param the {@code ImageReadParam} being used to read
     * the image.
     * @param numSrcBands the number of bands of the image as it exists
     * int the input source.
     * @param numDstBands the number of bands in the destination image
     * being written.
     *
     * @exception IllegalArgumentException if {@code param}
     * contains an invalid specification of a source and/or
     * destination band subset.
     */
    protected static void checkReadParamBandSettings(ImageReadParam param,
                                                     int numSrcBands,
                                                     int numDstBands) {
        // A null param is equivalent to srcBands == dstBands == null.
        int[] srcBands = null;
        int[] dstBands = null;
        if (param != null) {
            srcBands = param.getSourceBands();
            dstBands = param.getDestinationBands();
        }

        int paramSrcBandLength =
            (srcBands == null) ? numSrcBands : srcBands.length;
        int paramDstBandLength =
            (dstBands == null) ? numDstBands : dstBands.length;

        if (paramSrcBandLength != paramDstBandLength) {
            throw new IllegalArgumentException("ImageReadParam num source & dest bands differ!");
        }

        if (srcBands != null) {
            for (int i = 0; i < srcBands.length; i++) {
                if (srcBands[i] >= numSrcBands) {
                    throw new IllegalArgumentException("ImageReadParam source bands contains a value >= the number of source bands!");
                }
            }
        }

        if (dstBands != null) {
            for (int i = 0; i < dstBands.length; i++) {
                if (dstBands[i] >= numDstBands) {
                    throw new IllegalArgumentException("ImageReadParam dest bands contains a value >= the number of dest bands!");
                }
            }
        }
    }
	/**
	 * Fix size to be compatible with "bad size" ex.: 25x25 (instead of 24x24)
	 * @param size width or height
	 * @return fixed width or height 
	 */
	public int fixSize(int size) {
		while(size % 4 != 0) {
			size++;
		}
		return size;
	}

}
