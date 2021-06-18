package com.github.kilianB.hash;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.util.logging.Logger;

import dev.brachtendorf.graphics.ColorUtil;
import com.github.kilianB.hashAlgorithms.HashingAlgorithm;

import javafx.scene.paint.Color;

/**
 * A fuzzy hash is an aggregation of multiple hashes mapped to a single mean
 * hash representing the average hash while keeping track of fractional
 * (probability) bits. This kind of composite hash is suited to represent
 * clustered hashes and minimizing the distance to all members added.
 * 
 * <p>
 * To receive reasonable results a fuzzy hash should only contain hashes created
 * by the same algorithm.
 * 
 * <pre>
 * <code>
 * Combining three hashes 
 * H1:  1001
 * H2:  1011
 * H3:  1111
 * -------
 * Res: 1011 (used for hamming distances and getHashValue())
 * </code>
 * </pre>
 * 
 * In the above case the first and last bit have a certainty of 100%. While the
 * middle bits are only present in 66% of the added hashes. The
 * <code>weighted distance</code> will takes those certainties into account
 * while the original distance method inherited from will calculate the distance
 * to the modus bit for each bit.
 * 
 * <p>
 * <b>Implnote:</b> Opposed to the original {@link com.github.kilianB.hash.Hash}
 * equals and hashcode are not overwritten to ensure correct functionality in
 * hash collections after factoring in mutable fields. To check if hashes are
 * equals calculate the distance between the hashes instead.
 * 
 * @author Kilian
 * @since 3.0.0
 */
public class FuzzyHash extends Hash {

	private static final long serialVersionUID = 1395094691469035167L;

	private static final Logger LOGGER = Logger.getLogger(FuzzyHash.class.getSimpleName());

	/**
	 * The difference in 1's or 0 bits added for each position. Positive values
	 * indicate more 1's negative more 0's.
	 */
	protected int[] bits;

	/**
	 * //@formatter:off
	 * The probability of a bit being a 0 or 1
	 * In range of [-1,1]. 
	 * -1 Indicating that a bit is always 0 
	 *  1 that the bit is always 1 
	 *  0 that the bit is equally likely to be 0 or 1
	 *  //@formatter:on
	 */
	private double[] bitWeights;

	/**
	 * The distance of each bit to a 1 bit in range [0-1]
	 */
	private double[] bitDistance;

	// Lazily update fields

	/** Requires an update of the hashValue */
	public transient boolean dirtyBits;

	/** Requires an update of the bit weights */
	private transient boolean dirtyWeights;

	/** Requires an update of the distance array */
	private transient boolean dirtyDistance;

	/** The number of hashes contained in this composite hash */
	private int numHashesAdded;

	/**
	 * The maximum possible error when computing the normalized hamming distance and
	 * the weighted hamming distance
	 */
	private double maxError;

	/**
	 * Create an empty fuzzy hash object with a 0 bit hash length and a undefined
	 * algorithm id. These values will be populated as soon as the first hash is
	 * added.
	 */
	public FuzzyHash() {
		super(BigInteger.ZERO, 0, Integer.MAX_VALUE);
	}

	/**
	 * Create a fuzzy hahs by merging the supplied hashes together. The hashes are
	 * all expected to be created by the same algorithm.
	 * <p>
	 * The fuzzy hash will adapt the algorithm id and bit length of the first
	 * supplied hash.
	 * 
	 * @param hashs the hashes to merge.
	 */
	public FuzzyHash(Hash... hashs) {
		super(BigInteger.ZERO, 0, Integer.MAX_VALUE);
		merge(hashs);
	}

	/**
	 * Initialize the hash fields with the first added hash. as soon as we know the
	 * correct length
	 * 
	 * @param algorithmId the algorithm id to set this fuzzy hash to
	 * @param hashLength  the hash length of the hash
	 */
	private void initHash(int algorithmId, int hashLength) {
		this.algorithmId = algorithmId;
		this.hashLength = hashLength;
		bits = new int[hashLength];
		bitWeights = new double[hashLength];
		bitDistance = new double[hashLength];
	}

	/**
	 * Merge a hash into this object and lazily update the values as required. A
	 * merge operation looks at every individual bit and increments or decrements
	 * the counter prompting a recomputation of the underlying hash value if
	 * necessary.
	 * 
	 * <p>
	 * Merging multiple hashes will result in a hash which has to lowest summed
	 * distance to it's members.
	 *
	 * <p>
	 * Creating composite hashes is only reasonable for hashes generated by the same
	 * algorithm. This method will throw an IllegalArgumentException if the hashes
	 * are not created by the same algorithm or are of different length
	 * 
	 * @param hash the hash to merge
	 */
	public void merge(Hash hash) {

		// Init algoId
		if (algorithmId == Integer.MAX_VALUE) {
			initHash(hash.getAlgorithmId(), hash.getBitResolution());
		}

		if (hash.getBitResolution() == getBitResolution() && this.algorithmId == hash.getAlgorithmId()) {
			mergeFast(hash);
		} else {
			throw new IllegalArgumentException("Can't merge hashes with unequal length or algorithmIds");
		}
	}

	/**
	 * Merge multiple hashes into this object and lazily update the values as
	 * required. A merge operation looks at every individual bit and increments or
	 * decrements the counter prompting a recomputation of the underlying hash value
	 * if necessary.
	 * 
	 * <p>
	 * Merging multiple hashes will result in a hash which has to lowest summed
	 * distance to it's members.
	 *
	 * <p>
	 * Creating composite hashes is only reasonable for hashes generated by the same
	 * algorithm. This method will throw an IllegalArgumentException if the hashes
	 * are not created by the same algorithm or are of different length
	 * 
	 * @param hash the hash to merge
	 */
	public void merge(Hash... hash) {

		if (hash.length == 0) {
			throw new IllegalArgumentException("Please provide at least 1 hash to add to the matcher");
		}

		for (Hash h : hash) {
			merge(h);
		}
	}

	/**
	 * Merge a hash into this object and lazily update the values as required. A
	 * merge operation looks at every individual bit and increments or decrements
	 * the counter prompting a recomputation of the underlying hash value if
	 * necessary.
	 * 
	 * <p>
	 * Merging multiple hashes is a geometric averaging operation resulting in a
	 * hash with the lowest summed distance to it's members.
	 *
	 * <p>
	 * Opposed to {@link #merge(Hash)} this method does not perform any input
	 * validation. If incompatible hashes are added (especially hashes with
	 * differing lengths) the instance will be left in an undefined state and future
	 * calls to any of the methods may return unpredictable results.
	 * 
	 * @param hash to merge
	 */
	public void mergeFast(Hash hash) {

		if (algorithmId == Integer.MAX_VALUE) {
			initHash(hash.getAlgorithmId(), hash.getBitResolution());
		}

		for (int i = 0; i < getBitResolution(); i++) {
			bits[i] += hash.getBitUnsafe(i) ? 1 : -1;

			if (bits[i] == -1 || bits[i] == 1 || bits[i] == 0) {
				dirtyBits = true;
			}
		}
		numHashesAdded++;
		dirtyWeights = true;
		dirtyDistance = true;
	}

	/**
	 * Merge a fuzzy hash into this object and lazily update the values as required.
	 * A merge operation looks at every individual bit and increments or decrements
	 * the counter prompting a recomputation of the underlying hash value if
	 * necessary.
	 * 
	 * <p>
	 * Opposed to {@link #merge(Hash)} this method does not perform any input
	 * validation. If incompatible hashes are added (especially hashes with
	 * differing lengths) the instance will be left in an undefined state and future
	 * calls to any of the methods may return unpredictable results.
	 * 
	 * @param hash to merge
	 */
	public void mergeFast(FuzzyHash hash) {
		if (algorithmId == Integer.MAX_VALUE) {
			initHash(hash.getAlgorithmId(), hash.getBitResolution());
		}

		for (int i = 0; i < getBitResolution(); i++) {
			bits[i] += hash.bits[i];
		}

		if (hash.getAddedCount() > 0) {
			numHashesAdded += hash.getAddedCount();
		} else {
			numHashesAdded++;
		}

		dirtyBits = true;
		dirtyWeights = true;
		dirtyDistance = true;
	}

	/**
	 * Merge multiple hashes into this object and lazily update the values as
	 * required. A merge operation looks at every individual bit and increments or
	 * decrements the counter prompting a recomputation of the underlying hash value
	 * if necessary.
	 * 
	 * <p>
	 * Merging multiple hashes is a geometric averaging operation resulting in a
	 * hash with the lowest summed distance to it's members.
	 *
	 * <p>
	 * Opposed to {@link #merge(Hash)} this method does not perform any input
	 * validation. If incompatible hashes are added (especially hashes with
	 * differing lengths) the instance will be left in an undefined state and future
	 * calls to any of the methods may return unpredictable results.
	 * 
	 * @param hash to merge
	 */
	public void mergeFast(Hash... hash) {

		if (hash.length == 0) {
			throw new IllegalArgumentException("Please provide at least 1 hash to add to the matcher");
		}

		for (Hash h : hash) {
			mergeFast(h);
		}
	}

	/**
	 * Subtract a hash from this object and lazily update the values as required. A
	 * subtraction operation looks at every individual bit and increments or
	 * decrements the counter prompting a recomputation of the underlying hash value
	 * if necessary.
	 * 
	 * <p>
	 * Creating composite hashes is only reasonable for hashes generated by the same
	 * algorithm. This method will throw an IllegalArgumentException if the hashes
	 * are not created by the same algorithm or are of different length. Only hashes
	 * that were added beforehand should be subtracted.
	 * 
	 * @param hash to subtract
	 */
	public void subtract(Hash hash) {

		// Init algoId
		if (algorithmId == Integer.MAX_VALUE) {
			initHash(hash.getAlgorithmId(), hash.getBitResolution());
		}

		if (hash.getBitResolution() == getBitResolution() && this.algorithmId == hash.getAlgorithmId()) {
			subtractFast(hash);
		} else {
			throw new IllegalArgumentException("Can't subtract hashes with unequal length or algorithmIds");
		}
	}

	/**
	 * Subtract a hash from this object and lazily update the values as required. A
	 * subtraction operation looks at every individual bit and increments or
	 * decrements the counter prompting a recomputation of the underlying hash value
	 * if necessary.
	 * 
	 * <p>
	 * Creating composite hashes is only reasonable for hashes generated by the same
	 * algorithm. This method will throw an IllegalArgumentException if the hashes
	 * are not created by the same algorithm or are of different length. Only hashes
	 * that were added beforehand should be subtracted.
	 * 
	 * @param hash to subtract
	 */
	public void subtractFast(Hash hash) {

		if (algorithmId == Integer.MAX_VALUE) {
			initHash(hash.getAlgorithmId(), hash.getBitResolution());
		}

		for (int i = 0; i < getBitResolution(); i++) {
			bits[i] -= hash.getBitUnsafe(i) ? 1 : -1;

			if (bits[i] == -1 || bits[i] == 1 || bits[i] == 0) {
				dirtyBits = true;
			}
		}
		numHashesAdded--;
		dirtyWeights = true;
		dirtyDistance = true;
	}

	/**
	 * Calculate the normalized weighted distance between the supplied hash and this
	 * hash.
	 * 
	 * Opposed to the hamming distance the weighted distance takes partial bits into
	 * account.
	 * 
	 * e.g. if this fuzzy hashes first bit hash a probability of 70% being a 0 it
	 * will have a weighted distance of .7 if it's a 1.
	 * 
	 * Be aware that this method id much more expensive than calculating the simple
	 * distance between 2 ordinary hashes. (1 quick xor vs multiple calculations per
	 * bit).
	 * 
	 * @param h The hash to calculate the distance to
	 * @return similarity value ranging between [0 - 1]
	 */
	public double weightedDistance(Hash h) {

		ensureUpToDateDistance();

		double hammingDistance = 0;
		for (int bit = hashLength - 1; bit >= 0; bit--) {

			if (h.getBitUnsafe(bit)) {
				hammingDistance += bitDistance[bit];
			} else {
				hammingDistance += 1 - bitDistance[bit];
			}
		}
		return hammingDistance / hashLength;
	}

	/**
	 * Calculate the normalized weighted distance between two fuzzy hashes
	 * 
	 * Opposed to the hamming distance the weighted distance takes partial bits into
	 * account.
	 * 
	 * e.g. if this fuzzy hashes first bit hash a probability of 70% being a 0 and
	 * the second fuzzyhash has a 60% probability the distance will be 10%.
	 * 
	 * Be aware that this method id much more expensive than calculating the simple
	 * distance between 2 ordinary hashes. (1 quick xor vs multiple calculations per
	 * bit).
	 * 
	 * @param h The hash to calculate the distance to
	 * @return similarity value ranging between [0 - 1]
	 */
	public double weightedDistance(FuzzyHash h) {
		ensureUpToDateDistance();
		h.ensureUpToDateDistance();
		try {
			double hammingDistance = 0;
			for (int bit = 0; bit < bitDistance.length; bit++) {
				hammingDistance += Math.abs(bitDistance[bit] - h.bitDistance[bit]);
			}
			return hammingDistance / hashLength;
		} catch (NullPointerException np) {
			LOGGER.severe(
					"Null pointer exception in weighted distance calculation. One of the hashes is empty and not initialized");
			throw np;
		}
	}

	/**
	 * Calculate the squared normalized weighted distance between the supplied hash
	 * and this hash.
	 * 
	 * Opposed to the hamming distance the weighted distance takes partial bits into
	 * account.
	 * 
	 * e.g. if this fuzzy hashes first bit hash a probability of 70% being a 0 it
	 * will have a weighted distance of .7^2 if it's a 1.
	 * 
	 * Be aware that this method id much more expensive than calculating the simple
	 * distance between 2 ordinary hashes. (1 quick xor vs multiple calculations per
	 * bit).
	 * 
	 * @param h The hash to calculate the distance to
	 * @return similarity value ranging between [0 - 1]
	 */
	public double squaredWeightedDistance(Hash h) {

		ensureUpToDateDistance();

		double hammingDistance = 0;
		for (int bit = hashLength - 1; bit >= 0; bit--) {

			if (h.getBitUnsafe(bit)) {
				hammingDistance += bitDistance[bit] * bitDistance[bit];
			} else {
				hammingDistance += (1 - bitDistance[bit]) * (1 - bitDistance[bit]);
			}
		}
		return hammingDistance / hashLength;
	}

	/**
	 * Calculate the squared normalized weighted distance between two fuzzy hashes
	 * 
	 * Opposed to the hamming distance the weighted distance takes partial bits into
	 * account.
	 * 
	 * e.g. if this fuzzy hashes first bit hash a probability of 70% being a 0 and
	 * the second fuzzyhash has a 60% probability the distance will be 10%^2.
	 * 
	 * Be aware that this method is much more expensive than calculating the simple
	 * distance between 2 ordinary hashes. (1 quick xor vs multiple calculations per
	 * bit).
	 * 
	 * @param h The hash to calculate the distance to
	 * @return similarity value ranging between [0 - 1]
	 */
	public double squaredWeightedDistance(FuzzyHash h) {
		ensureUpToDateDistance();
		h.ensureUpToDateDistance();
		try {
			double hammingDistance = 0;
			for (int bit = 0; bit < bitDistance.length; bit++) {
				double temp = Math.abs(bitDistance[bit] - h.bitDistance[bit]);
				hammingDistance += temp * temp;
			}
			return hammingDistance / hashLength;
		} catch (NullPointerException np) {
			LOGGER.severe(
					"Null pointer exception in weighted distance calculation. One of the hashes is empty and not initialized");
			throw np;
		}
	}

	public double getMaximalError() {

		if (numHashesAdded == 0) {
			return 0;
		}
		// TODO we don't need to recompute it every single time
		maxError = 0;
		for (int bit = 0; bit < bits.length; bit++) {
			maxError += getMaxUncertainty(bit);
		}
		return maxError / bits.length;
	}

	private void computeWeights() {

		if (numHashesAdded == 0) {
			// ArrayUtil.fillArray(bitWeights,()->{return 0d;});
			// Probably faster?
			bitWeights = new double[bitWeights.length];
		} else {
			for (int i = 0; i < getBitResolution(); i++) {
				// bitWeights[i] = bits[i] / (double) numHashesAdded;

				// (((x - y) / 2) + x )/ x
				if (bits[i] == 0.0) {
					bitWeights[i] = 0;
				} else {
					if (bits[i] < 0) {
						bitWeights[i] = -((numHashesAdded + bits[i]) / 2d - bits[i]) / numHashesAdded;
					} else {
						bitWeights[i] = ((numHashesAdded - bits[i]) / 2d + bits[i]) / numHashesAdded;
						// bitWeights[i] = (numHashesAdded + bits[i])/(2*numHashesAdded);
					}
				}
			}
		}
		dirtyWeights = false;
	}

	private void computeDistance() {
		// Compute distance to one bit
		for (int bit = hashLength - 1; bit >= 0; bit--) {

			if (bits[bit] > 0) {
				bitDistance[bit] = (numHashesAdded - bits[bit]) / 2d / numHashesAdded;
			} else {
				bitDistance[bit] = ((numHashesAdded + bits[bit]) / 2d - bits[bit]) / numHashesAdded;
			}
		}
		dirtyDistance = false;
	}

	/**
	 * Removing all previous knowledge of hashes added while keeping the current
	 * cluster hash in place treating it as the only hash added.
	 * 
	 * <p>
	 * This operation is the in place equivalent to
	 * 
	 * <pre>
	 * <code>
	 * 
	 * FuzzyHash fuzzy ...
	 * ...
	 * ...
	 * 
	 * FuzzyHash temp = new FuzzyHash();
	 * temp.mergeFast(new Hash(fuzzy.getHashValue(),fuzzy.getBitResolution,fuzzy.getAlgorithmId()));
	 * fuzzy = temp;
	 * </code>
	 * </pre>
	 */
	public void reset() {
		updateHash();
		numHashesAdded = 1;
		bits = new int[hashLength];

		// Set bits according to the has
		for (int i = hashLength - 1; i >= 0; i--) {
			if (super.getBitUnsafe(i)) {
				bits[i] = 1;
			} else {
				bits[i] = -1;
			}
		}
		computeWeights();
		computeDistance();
	}

	private void updateHash() {
		hashValue = BigInteger.ZERO;
		for (int i = hashLength - 1; i >= 0; i--) {
			// XXX we only have a binary representation. A bit weight of 0 usually means
			// that
			// the bit is undefined and should not be counted a 0 or 1. Due to this the
			// hamming distance
			// and normalized hamming distance will be skewed for sparsely populated hashes
			if (bits[i] > 0) {
				hashValue = hashValue.shiftLeft(1).add(BigInteger.ONE);
			} else {
				hashValue = hashValue.shiftLeft(1);
			}
		}
		dirtyBits = false;
	}

	/**
	 * Get the certainty of a bit at the given bit position.
	 * 
	 * <p>
	 * The returned value is in the range [-1,1]. A negative value indicates that
	 * the bit is more likely to be a 0. A positive value indicates the bit to be
	 * more likely a 1.
	 * <p>
	 * The value is the probability of a randomly drawn hash from the set of
	 * previously merged hashes nth bit being 0 or 1.
	 * 
	 * @param position the bit position.
	 * @return The certainty in range of [-1,1].
	 */
	public double getCertainty(int position) {
		ensureUpToDateWeights();
		return bitWeights[position];
	}

	/**
	 * Return a mask indicating if the bits are above a certain uncertainty.
	 * <p>
	 * The certainty of a hash is calculated by comparing the number of 0 bits at
	 * position n with the number of 1 bits at position n of all the added hashes.
	 * If all hashes agree the certainty is 100%. If 50% of the hashes contain 0
	 * bits and the other half contains 1 bits the bit has a certainty of 0.
	 * 
	 * <p>
	 * Be aware that index 0 relegates to the rightmost bit. Printing the array will
	 * show the boolean values in reverse order.
	 * 
	 * <p>
	 * This method only returns useable results as soon as one hash was added-
	 * 
	 * 
	 * @param certainty the certainty [0-1] up to which bits will be included. In
	 *                  other words, if a bit is more certain than specified by this
	 *                  argument it will not be included in the result.
	 * @return a boolean array indicating which bits are uncertain
	 */
	public boolean[] getUncertaintyMask(double certainty) {

		ensureUpToDateWeights();

		boolean[] uncertainBits = new boolean[getBitResolution()];

		for (int i = 0; i < uncertainBits.length; i++) {
			uncertainBits[i] = !(bitWeights[i] > certainty || bitWeights[i] < -certainty);
		}
		return uncertainBits;
	}

	/**
	 * Return a simple hash containing only the bits that are below the specified
	 * uncertainty. This hash can be used to further compare hashes matched to this
	 * cluster while ignoring bits that are likely to be similar.
	 * 
	 * <p>
	 * To obtain compatible hashes take a look at
	 * {{@link #toUncertaintyHash(Hash, double)} to escape other hashes the same
	 * way.
	 * 
	 * @param certainty the certainty [0-1] up to which bits will be included. In
	 *                  other words, if a bit is more certain than specified by this
	 *                  argument it will not be included in the result.
	 * @return a hash with the certain bits discarded.
	 */
	public Hash getUncertaintyHash(double certainty) {
		return toUncertaintyHash(this, certainty);
	}

	/**
	 * Escape the given hash the same way {@link #getUncertaintyHash(double)}
	 * escapes this fuzzy hash.
	 * <p>
	 * Return a simple hash containing only the bits that are below the specified
	 * uncertainty of the fuzzy hash. This hash can be used to further compare
	 * hashes matched to this cluster while ignoring bits that are likely to be
	 * similar.
	 * 
	 * <p>
	 * To get reasonable results the source hash has to be compatible with the fuzzy
	 * hash, meaning that it was generated by the same algorithm and settings as the
	 * hashes which make up the fuzzy hash.
	 * 
	 * @param source    the hash to convert.
	 * @param certainty threshold to indicate which bits to discard
	 * @return a hash with the certain bits discarded.
	 */
	public Hash toUncertaintyHash(Hash source, double certainty) {

		ensureUpToDateWeights();

		int bitCount = getBitResolution();
		BigInteger hashValue = BigInteger.ZERO;

		int newBitCount = 0;
		int hashCode = algorithmId;
		for (int i = 0; i < bitCount; i++) {
			if (!(bitWeights[i] > certainty || bitWeights[i] < -certainty)) {
				if (source.getBitUnsafe(i)) {
					hashValue = hashValue.shiftLeft(1).add(BigInteger.ONE);
				} else {
					hashValue = hashValue.shiftLeft(1);
				}
				newBitCount++;
				hashCode = 31 * hashCode + i;
			}
		}
		return new Hash(hashValue, newBitCount, 31 * hashCode + newBitCount);
	}

	/**
	 * Ensure that the weight probability array is up to date.
	 * 
	 * Call this method when ever the array is needed.
	 */
	private void ensureUpToDateWeights() {
		if (dirtyWeights) {
			computeWeights();
		}
	}

	/**
	 * Ensure that the hashValue is up to date.
	 * 
	 * Call this method when ever the value is needed.
	 */
	private void ensureUpToDateHash() {
		if (dirtyBits) {
			updateHash();
		}
	}

	/**
	 * Ensure that the distance array is up to date.
	 * 
	 * Call this method when ever the array is needed.
	 */
	private void ensureUpToDateDistance() {
		if (dirtyDistance) {
			computeDistance();
		}
	}

	// Override methods from base implementation

	@Override
	public BigInteger getHashValue() {
		ensureUpToDateHash();
		return super.getHashValue();
	}

	@Override
	public boolean getBitUnsafe(int position) {
		return bits[position] > 0;
	}

	@Override
	public int hammingDistance(Hash h) {
		ensureUpToDateHash();
		return super.hammingDistanceFast(h);
	}

	@Override
	public int hammingDistanceFast(Hash h) {
		ensureUpToDateHash();
		return super.hammingDistanceFast(h);
	}

	@Override
	public int hammingDistanceFast(BigInteger bInt) {
		ensureUpToDateHash();
		return super.hammingDistanceFast(bInt);
	}

	@Override
	public double normalizedHammingDistance(Hash h) {
		ensureUpToDateHash();
		return super.normalizedHammingDistanceFast(h);
	}

	@Override
	public double normalizedHammingDistanceFast(Hash h) {
		ensureUpToDateHash();
		return super.normalizedHammingDistanceFast(h);
	}

	@Override
	public byte[] toByteArray() {
		ensureUpToDateHash();
		return super.toByteArray();
	}

	// Falls back to the default hashcode and equals method since we can not!
	// guarantee inmutability
	// of the fields

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}

	@Override
	public boolean equals(Object obj) {
		return (this == obj);
	}

	@Override
	public String toString() {
		ensureUpToDateHash();
		return super.toString();
	}

	/**
	 * Creates a visual representation of the hash mapping the hash values to the
	 * section of the rescaled image used to generate the hash.
	 * 
	 * @param blockSize Stretch factor.Due to rescaling the image was shrunk down
	 *                  during hash creation.
	 * @return A black and white image representing the individual bits of the hash
	 */
	public BufferedImage toImage(int blockSize) {

		ensureUpToDateHash();

		// Build color palette
		Color[] lowerCol = ColorUtil.ColorPalette.getPalette(15, Color.web("#ff642b"), Color.web("#ffff7c"));
		Color[] higherCol = ColorUtil.ColorPalette.getPalette(15, Color.web("#ffff7c"), Color.GREEN);

		Color[] colors = new Color[lowerCol.length + higherCol.length];
		System.arraycopy(lowerCol, 0, colors, 0, lowerCol.length);
		System.arraycopy(higherCol, 0, colors, lowerCol.length, higherCol.length);

		// Build color index array

		int cLength = colors.length;

		int[] colorIndex = new int[hashLength];
		for (int i = 0; i < hashLength; i++) {
			colorIndex[i] = (int) (((bits[i] / (double) numHashesAdded) + 1) / 2 * (cLength - 1));
		}
		return toImage(colorIndex, colors, blockSize);
	}

	public BufferedImage toImage(int blockSize, HashingAlgorithm hashingAlgorithm) {

		ensureUpToDateHash();

		// Build color palette
		Color[] lowerCol = ColorUtil.ColorPalette.getPalette(15, Color.web("#ff642b"), Color.web("#ffff7c"));
		Color[] higherCol = ColorUtil.ColorPalette.getPalette(15, Color.web("#ffff7c"), Color.GREEN);

		Color[] colors = new Color[lowerCol.length + higherCol.length];
		System.arraycopy(lowerCol, 0, colors, 0, lowerCol.length);
		System.arraycopy(higherCol, 0, colors, lowerCol.length, higherCol.length);

		// Build color index array

		int cLength = colors.length;

		int[] colorIndex = new int[hashLength];
		for (int i = 0; i < hashLength; i++) {
			colorIndex[i] = (int) (((bits[i] / (double) numHashesAdded) + 1) / 2 * (cLength - 1));
		}

		return hashingAlgorithm.createAlgorithmSpecificHash(this).toImage(colorIndex, colors, blockSize);
	}

	/**
	 * Return the number of hashes that currently make up this hash. After calling
	 * reset the added count will be set to 1.
	 * 
	 * @return the count
	 * 
	 */
	public int getAddedCount() {
		return numHashesAdded;
	}

	/**
	 * Return the maximum fuzzy distance for this bit. The maximum distance is the
	 * distance to either the 0 bit or the 1 bit which ever is greater.
	 * 
	 * @param bitIndex the index of the bit
	 * @return the maximum distance
	 */
	public double getMaxUncertainty(int bitIndex) {
		return getWeightedDistance(bitIndex, bits[bitIndex] < 0);
	}

	/**
	 * Gets the weighted distance of this bit in the range [0-1]
	 * 
	 * @param bitIndex the position in the hash
	 * @param bit      if true the distance to a 1 bit will be calculated. if false
	 *                 the distance to a 0 bit.
	 * @return the distance of full probable bit to this hashes bit.
	 */
	public double getWeightedDistance(int bitIndex, boolean bit) {
		ensureUpToDateDistance();
		if (bit) {
			return bitDistance[bitIndex];
		} else {
			return 1 - bitDistance[bitIndex];
		}
	}

	public void toFile(File saveLocation) throws IOException {
		try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(saveLocation))) {
			oos.writeObject(this);
		}
	}

	/**
	 * Reads a hash from a serialization file and returns it. Only hashes can be
	 * read from file that got saved by the same class instance using
	 * {@link #toFile(File)};
	 * 
	 * @param source The file this hash can be read from.
	 * @return a hash object
	 * @throws IOException            If an error occurs during file read
	 * @throws ClassNotFoundException if the class used to serialize this hash can
	 *                                not be found
	 * @since 3.0.0
	 */
	public static FuzzyHash fromFile(File source) throws IOException, ClassNotFoundException {
		try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(source))) {
			FuzzyHash fuzzy = (FuzzyHash) ois.readObject();
			fuzzy.dirtyBits = true;
			fuzzy.dirtyDistance = true;
			fuzzy.dirtyWeights = true;
			fuzzy.ensureUpToDateDistance();
			fuzzy.ensureUpToDateHash();
			fuzzy.ensureUpToDateWeights();
			return fuzzy;
		}
	}

}
