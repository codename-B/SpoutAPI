/*
 * This file is part of SpoutAPI (http://www.spout.org/).
 *
 * SpoutAPI is licensed under the SpoutDev License Version 1.
 *
 * SpoutAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the SpoutDev License Version 1.
 *
 * SpoutAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the SpoutDev License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://www.spout.org/SpoutDevLicenseV1.txt> for the full license,
 * including the MIT license.
 */
package org.spout.api.util.map.concurrent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.spout.api.basic.blocks.BlockFullState;
import org.spout.api.datatable.DatatableSequenceNumber;
import org.spout.api.geo.cuboid.Blockm;

/**
 * This store stores block data for each chunk.
 * Each block can either store a short id, or a short id, a short data
 * value and a reference to a &lt;T&gt; object.
 */
public class AtomicBlockStore<T> {
	private final int side;
	private final int shift;
	private final int doubleShift;
	private final AtomicShortArray blockIds;
	private AtomicIntReferenceArrayStore<T> auxStore;
	private final AtomicBoolean compressing = new AtomicBoolean(false);
	private final byte[] dirtyX;
	private final byte[] dirtyY;
	private final byte[] dirtyZ;
	private final AtomicInteger dirtyBlocks = new AtomicInteger(0);

	public AtomicBlockStore(int shift) {
		this(shift, 10);
	}

	public AtomicBlockStore(int shift, int dirtySize) {
		this.side = 1 << shift;
		this.shift = shift;
		this.doubleShift = shift << 1;
		blockIds = new AtomicShortArray(side * side * side);
		auxStore = new AtomicIntReferenceArrayStore<T>(side * side * side);
		dirtyX = new byte[dirtySize];
		dirtyY = new byte[dirtySize];
		dirtyZ = new byte[dirtySize];
	}

	/**
	 * Gets the sequence number associated with a block location.<br>
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @return the sequence number, or DatatableSequenceNumber.ATOMIC for a single short record
	 */
	public final int getSequence(int x, int y, int z) {
		checkCompressing();
		int index = getIndex(x, y, z);
		while (true) {
			checkCompressing();

			int blockId = blockIds.get(index);
			if (!auxStore.isReserved(blockId)) {
				return DatatableSequenceNumber.ATOMIC;
			} else {
				int sequence = auxStore.getSequence(blockId);
				if (sequence != DatatableSequenceNumber.UNSTABLE) {
					return sequence;
				}
			}
		}
	}

	/**
	 * Gets the block id for a block at a particular location.<br>
	 * <br>
	 * Block ids range from 0 to 65535.
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @return the block id
	 */
	public final int getBlockId(int x, int y, int z) {
		int index = getIndex(x, y, z);
		while (true) {
			checkCompressing();

			int seq = getSequence(x, y, z);
			short blockId = blockIds.get(index);
			if (auxStore.isReserved(blockId)) {
				blockId = auxStore.getId(blockId);
				int seq2 = getSequence(x, y, z);
				if (seq == seq2) {
					return blockId & 0x0000FFFF;
				}
			} else {
				return blockId & 0x0000FFFF;
			}
		}
	}

	/**
	 * Gets the block data for a block at a particular location.<br>
	 * <br>
	 * Block data ranges from 0 to 65535.
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @return the block data
	 */
	public final int getData(int x, int y, int z) {
		int index = getIndex(x, y, z);
		while (true) {
			checkCompressing();

			int seq = getSequence(x, y, z);
			short blockId = blockIds.get(index);
			if (auxStore.isReserved(blockId)) {
				blockId = auxStore.getData(blockId);
				int seq2 = getSequence(x, y, z);
				if (seq == seq2) {
					return blockId & 0x0000FFFF;
				}
			} else {
				return 0;
			}
		}
	}

	/**
	 * Gets the block auxiliary data for a block at a particular location.<br>
	 * <br>
	 * Block data ranges from 0 to 65535.
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @return the block auxiliary data
	 */
	public final T getAuxData(int x, int y, int z) {
		int index = getIndex(x, y, z);
		while (true) {
			checkCompressing();

			int seq = getSequence(x, y, z);
			short blockId = blockIds.get(index);
			if (auxStore.isReserved(blockId)) {
				T auxData = auxStore.getAuxData(blockId);
				int seq2 = getSequence(x, y, z);
				if (seq == seq2) {
					return auxData;
				}
			} else {
				return null;
			}
		}
	}

	/**
	 * Atomically gets the full set of data associated with the block.<br>
	 * <br>
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @param fullState a BlockFullState object to store the return value, or null to generate a new one
	 * @return the full state of the block
	 */
	public final BlockFullState<T> getFullData(int x, int y, int z) {
		return getFullData(x, y, z, null);
	}
	/**
	 * Atomically gets the full set of data associated with the block.<br>
	 * <br>
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @param fullState a BlockFullState object to store the return value, or null to generate a new one
	 * @return the full state of the block
	 */
	public final BlockFullState<T> getFullData(int x, int y, int z, BlockFullState<T> fullData) {
		if (fullData == null) {
			fullData = new BlockFullState<T>();
		}
		int index = getIndex(x, y, z);
		while (true) {
			checkCompressing();

			int seq = getSequence(x, y, z);
			short blockId = blockIds.get(index);
			if (auxStore.isReserved(blockId)) {
				fullData.setId(auxStore.getId(blockId));
				fullData.setData(auxStore.getData(blockId));
				fullData.setAuxData(auxStore.getAuxData(blockId));
				int seq2 = getSequence(x, y, z);
				if (seq == seq2) {
					return fullData;
				}
			} else {
				fullData.setId(blockId);
				fullData.setData((short)0);
				fullData.setAuxData(null);
				return fullData;
			}
		}
	}

	/**
	 * Sets the block id, data and auxData for the block at (x, y, z).<br>
	 * <br>
	 * If the data is 0 and the auxData is null, then the block will be stored as a single short.<br>
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @param fullState the new state of the Block
	 */
	public final void setBlock(int x, int y, int z, BlockFullState<T> fullState) {
		setBlock(x, y, z, fullState.getId(), fullState.getData(), fullState.getAuxData());
	}

	/**
	 * Sets the block id, data and auxData for the block at (x, y, z).<br>
	 * <br>
	 * If the data is 0 and the auxData is null, then the block will be stored as a single short.<br>
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @param id the block id
	 * @param data the block data
	 * @param auxData the block auxiliary data
	 */
	public final void setBlock(int x, int y, int z, short id, short data, T auxData) {
		int index = getIndex(x, y, z);
		while (true) {
			checkCompressing();

			short oldBlockId = blockIds.get(index);
			boolean oldReserved = auxStore.isReserved(oldBlockId);
			if (data == 0 && auxData == null && !auxStore.isReserved(id)) {
				if (!blockIds.compareAndSet(index, oldBlockId, id)) {
					continue;
				}
				if (oldReserved) {
					if (!auxStore.remove(oldBlockId)) {
						throw new IllegalStateException("setBlock() tried to remove old record, but it had already been removed");
					}
				}
				markDirty(x, y, z);
				return;
			} else {
				int newIndex = auxStore.add(id, data, auxData);
				if (!blockIds.compareAndSet(index, oldBlockId, (short)newIndex)) {
					if (auxStore.remove(newIndex)) {
						throw new IllegalStateException("setBlock() tried to remove old record, but it had already been removed");
					}
					continue;
				}
				if (oldReserved) {
					if (!auxStore.remove(oldBlockId)) {
						throw new IllegalStateException("setBlock() tried to remove old record, but it had already been removed");
					}
				}
				markDirty(x, y, z);
				return;
			}
		}
	}

	/**
	 * Sets the block id, data and auxData for the block at (x, y, z), if the current data matches the expected data.<br>
	 *
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @param expectId the expected block id
	 * @param expectData the expected block data
	 * @param expectAuxData the expected block auxiliary data
	 * @param newId the new block id
	 * @param newData the new block data
	 * @param newAuxData the new block auxiliary data
	 * @return true if the block was set
	 */
	public final boolean compareAndSetBlock(int x, int y, int z, short expectId, short expectData, T expectAuxData, short newId, short newData, T newAuxData) {
		int index = getIndex(x, y, z);

		while (true) {
			checkCompressing();

			short oldBlockId = blockIds.get(index);
			boolean oldReserved = auxStore.isReserved(oldBlockId);

			if (!oldReserved) {
				if (blockIds.get(index) != expectId || expectData != 0 || expectAuxData != null) {
					return false;
				}
			} else {
				int seq1 = auxStore.getSequence(oldBlockId);
				short oldId = auxStore.getId(oldBlockId);
				short oldData = auxStore.getData(oldBlockId);
				T oldAuxData = auxStore.getAuxData(oldBlockId);
				int seq2 = auxStore.getSequence(oldBlockId);
				if (seq1 != seq2) {
					continue;
				}
				if (oldId != expectId || oldData != expectData || oldAuxData != expectAuxData) {
					return false;
				}
			}

			if (newData == 0 && newAuxData == null && !auxStore.isReserved(newId)) {
				if (!blockIds.compareAndSet(index, oldBlockId, newId)) {
					continue;
				}
				if (oldReserved) {
					if (!auxStore.remove(oldBlockId)) {
						throw new IllegalStateException("setBlock() tried to remove old record, but it had already been removed");
					}
				}
				markDirty(x, y, z);
				return true;
			} else {
				int newIndex = auxStore.add(newId, newData, newAuxData);
				if (!blockIds.compareAndSet(index, oldBlockId, (short)newIndex)) {
					if (!auxStore.remove(newIndex)) {
						throw new IllegalStateException("setBlock() tried to remove old record, but it had already been removed");
					}
					continue;
				}
				if (oldReserved) {
					if (!auxStore.remove(oldBlockId)) {
						throw new IllegalStateException("setBlock() tried to remove old record, but it had already been removed");
					}
				}
				markDirty(x, y, z);
				return true;
			}
		}
	}

	/**
	 * Sets the block id, data and auxData for the block at (x, y, z), if the current data matches the expected data.<br>
	 * <br>
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @param expect the expected block value
	 * @param newValue the new block value
	 * @return true if the block was set
	 */
	public final boolean compareAndSetBlock(int x, int y, int z, BlockFullState<T> expect, BlockFullState<T> newValue) {
		return compareAndSetBlock(x, y, z, expect.getId(), expect.getData(), expect.getAuxData(), newValue.getId(), newValue.getData(), newValue.getAuxData());
	}

	/**
	 * Gets if the store would benefit from compression.<br>
	 * <br>
	 * If this method is called when the store is being accessed by another thread, it may give spurious results.
	 *
	 * @return true if compression would reduce the store size
	 */
	public boolean needsCompression() {
		return ((auxStore.getEntries() << 3) / 3) < auxStore.getSize();
	}

	/**
	 * Gets a short array containing the block ids in the store.<br>
	 * <br>
	 * If the store is updated while this snapshot is being taken, data tearing could occur.
	 *
	 * @return the array
	 */
	public short[] getBlockIdArray() {
		int length = blockIds.length();
		short[] array = new short[length];
		for (int i = 0; i < length; i++) {
			array[i] = blockIds.get(i);
		}
		return array;
	}

	/**
	 * Compresses the auxiliary store.<br>
	 * <br>
	 * This method should only be called when the store is guaranteed not to be accessed from any other thread.<br>
	 */
	public final void compress() {
		if (!compressing.compareAndSet(false, true)) {
			throw new IllegalStateException("Compression started while compression was in progress");
		}
		int length = side * side * side;
		AtomicIntReferenceArrayStore<T> newAuxStore = new AtomicIntReferenceArrayStore<T>(side * side * side);
		for (int i = 0; i < length; i++) {
			short blockId = blockIds.get(i);
			if (auxStore.isReserved(blockId)) {
				short storedId = auxStore.getId(blockId);
				short storedData = auxStore.getData(blockId);
				T storedAuxData = auxStore.getAuxData(blockId);
				int newIndex = newAuxStore.add(storedId, storedData, storedAuxData);
				if (!blockIds.compareAndSet(i, blockId, (short)newIndex)) {
					throw new IllegalStateException("Unstable block id data during compression step");
				}
			}
		}
		auxStore = newAuxStore;
		compressing.set(false);
	}

	/**
	 * Gets the size of the internal arrays
	 *
	 * @return the size of the arrays
	 */
	public final int getSize() {
		checkCompressing();
		return auxStore.getSize();
	}

	/**
	 * Gets the number of entries in the store
	 *
	 * @return the size of the arrays
	 */
	public final int getEntries() {
		checkCompressing();
		return auxStore.getEntries();
	}

	/**
	 * Gets if the dirty array has overflowed since the last reset.<br>
	 * <br>
	 * @return true if there was an overflow
	 */
	public boolean isDirtyOverflow() {
		return dirtyBlocks.get() >= dirtyX.length;
	}

	/**
	 * Gets if the store has been modified since the last reset of the dirty arrays
	 *
	 * @return true if the store is dirty
	 */
	public boolean isDirty() {
		return dirtyBlocks.get() > 0;
	}

	/**
	 * Resets the dirty arrays
	 */
	public void resetDirtyArrays() {
		dirtyBlocks.set(0);
	}

	/**
	 * Gets the position of the dirty block at a given index.<br>
	 * <br>
	 * If there is no block at that index, then the method return null.<br>
	 * <br>
	 * Note: the x, y and z values returned are the chunk coordinates,
	 * not the world coordinates and the method has no effect on the world field of the block.<br>
	 * @param i
	 * @param block
	 * @return
	 */
	public Blockm getDirtyBlock(int i, Blockm block) {
		if (i >= dirtyBlocks.get()) {
			return null;
		}
		block.setX(dirtyX[i] & 0xFF);
		block.setY(dirtyY[i] & 0xFF);
		block.setZ(dirtyZ[i] & 0xFF);
		return block;
	}

	private final int getIndex(int x, int y, int z) {
		return (x << doubleShift) + (z << shift) + y;
	}

	/**
	 * Marks a block as dirty.<br>
	 * <br>
	 * Updates for dirty blocks will be sent at the end of the tick.<br>
	 *
	 * @param x the x coordinate of the dirty block
	 * @param y the y coordinate of the dirty block
	 * @param z the z coordinate of the dirty block
	 */
	public void markDirty(int x, int y, int z) {
		int index = dirtyBlocks.getAndIncrement();
		if (index < dirtyX.length) {
			dirtyX[index] = (byte)x;
			dirtyY[index] = (byte)y;
			dirtyZ[index] = (byte)z;
		}
	}

	private final void checkCompressing() {
		if (compressing.get()) {
			throw new IllegalStateException("Attempting to access block store during compression phase");
		}
	}
}
