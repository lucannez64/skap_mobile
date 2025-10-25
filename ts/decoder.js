const KyPublicKeySize = 1568;
const KySecretKeySize = 3168;
const DiPublicKeySize = 2592;
const DiSecretKeySize = 4896;

/**
 * @typedef {Uint8Array} KyPublicKey
 */

/**
 * @typedef {Uint8Array} KySecretKey
 */

/**
 * @typedef {Uint8Array} DiPublicKey
 */

/**
 * @typedef {Uint8Array} DiSecretKey
 */

/**
 * @typedef CK
 * @property {string} email
 * @property {Uuid|null} id
 * @property {KyPublicKey} ky_p
 * @property {DiPublicKey} di_p
 */

/**
 * @typedef Client
 * @property {KyPublicKey} ky_p
 * @property {KySecretKey} ky_q
 * @property {DiPublicKey} di_p
 * @property {DiSecretKey} di_q
 * @property {Uint8Array|null} secret
 */

/**
 * @typedef Uuid
 * @property {Uint8Array} bytes
 */

/**
 * @typedef ClientEx
 * @property {Client} c
 * @property {CK} id
 */

/**
 * @typedef EP
 * @property {Uint8Array} ciphertext
 * @property {Uint8Array} nonce
 * @property {Uint8Array|null} nonce2
 */

/**
 * @typedef Password
 * @property {string} password
 * @property {string|null} app_id
 * @property {string} username
 * @property {string|null} description
 * @property {string|null} url
 * @property {string|null} otp
 */

/**
 * @typedef SharedPass
 * @property {Uint8Array} kem_ct - Texte chiffré KEM généré avec la clé publique du destinataire
 * @property {EP} ep - Le mot de passe chiffré avec la clé partagée
 */

/**
 * Encodes EP struct to Uint8Array
 * @param {EP} ep
 * @returns {Uint8Array}
 */
export function encodeEP(ep) {
	const ciphertextLen = ep.ciphertext.length;
	const nonceLen = ep.nonce.length;
	const nonce2Len = ep.nonce2 ? ep.nonce2.length : 0;
	const nonce2Present = ep.nonce2 ? 1 : 0;

	let totalLength = 0;
	totalLength += 8 + ciphertextLen; // ciphertext length + ciphertext
	totalLength += 8 + nonceLen; // nonce length + nonce
	totalLength += 1; // nonce2 present flag
	if (nonce2Present) {
		totalLength += 8 + nonce2Len; // nonce2 length + nonce2
	}

	const buffer = new ArrayBuffer(totalLength);
	const view = new DataView(buffer);
	let offset = 0;

	view.setBigUint64(offset, BigInt(ciphertextLen), true);
	offset += 8;
	for (let i = 0; i < ciphertextLen; ++i) {
		view.setUint8(offset++, ep.ciphertext[i]);
	}

	view.setBigUint64(offset, BigInt(nonceLen), true);
	offset += 8;
	for (let i = 0; i < nonceLen; ++i) {
		view.setUint8(offset++, ep.nonce[i]);
	}

	view.setUint8(offset++, nonce2Present);
	if (nonce2Present) {
		view.setBigUint64(offset, BigInt(nonce2Len), true);
		offset += 8;
		for (let i = 0; i < nonce2Len; ++i) {
			view.setUint8(offset++, ep.nonce2[i]);
		}
	}

	return new Uint8Array(buffer);
}

/**
 * Encodes Password struct to Uint8Array
 * @param {Password} passwordObject
 * @returns {Uint8Array | null}
 */
export function encodePassword(passwordObject) {
	// Check that required fields are provided
	if (
		!passwordObject ||
		typeof passwordObject.password !== 'string' ||
		typeof passwordObject.username !== 'string'
	) {
		console.error("encodePassword: Missing required fields 'password' and/or 'username'.");
		return null;
	}

	/**
	 * Encodes a string into a Uint8Array following the format expected by readString.
	 *
	 * Format:
	 * - 1 byte: length of the encoded string (as given by TextEncoder)
	 * - 7 bytes: reserved (set to 0)
	 * - n bytes: the UTF-8 encoded string bytes
	 */
	function encodeString(str) {
		const encoder = new TextEncoder();
		const encodedStr = encoder.encode(str);
		const len = encodedStr.length;

		// Create a new Uint8Array to hold the header (8 bytes) + encoded string data.
		const result = new ArrayBuffer(8 + len);
		const view = new DataView(result);
		let offset = 0;
		const bigNum = BigInt(len);
		view.setBigUint64(offset, bigNum, true);
		offset += 8;
		// The next 7 bytes are reserved (they are already 0 by default)
		// Copy the encoded string starting at offset 8.
		if (len > 0) {
			for (let i = 0; i < len; ++i) {
				view.setUint8(offset++, encodedStr[i]);
			}
		}
		const result2 = new Uint8Array(result);
		return result2;
	}

	/**
	 * Helper to encode an optional string field.
	 *
	 * Returns an object with a flag Uint8Array and, if present, the encoded string.
	 */
	function encodeOptional(str) {
		if (str !== undefined && str !== null) {
			const flag = new Uint8Array([1]);
			const encodedString = encodeString(str);
			return { flag, encodedString };
		} else {
			const flag = new Uint8Array([0]);
			return { flag, encodedString: null };
		}
	}

	// Parts will hold each Uint8Array fragment of the overall byte stream.
	const parts = [];

	// 1. Encode the password (required)
	parts.push(encodeString(passwordObject.password));

	// 2. Encode the app_id (optional)
	const appIdObj = encodeOptional(passwordObject.app_id);
	parts.push(appIdObj.flag);
	if (appIdObj.encodedString) {
		parts.push(appIdObj.encodedString);
	}

	// 3. Encode the username (required)
	parts.push(encodeString(passwordObject.username));

	// 4. Encode the description (optional)
	const descriptionObj = encodeOptional(passwordObject.description);
	parts.push(descriptionObj.flag);
	if (descriptionObj.encodedString) {
		parts.push(descriptionObj.encodedString);
	}

	// 5. Encode the url (optional)
	const urlObj = encodeOptional(passwordObject.url);
	parts.push(urlObj.flag);
	if (urlObj.encodedString) {
		parts.push(urlObj.encodedString);
	}

	// 6. Encode the otp (optional)
	const otpObj = encodeOptional(passwordObject.otp);
	parts.push(otpObj.flag);
	if (otpObj.encodedString) {
		parts.push(otpObj.encodedString);
	}

	// Calculate total length of the output buffer.
	const totalLength = parts.reduce((acc, part) => acc + part.length, 0);
	const combined = new Uint8Array(totalLength);

	// Copy each part into the combined output.
	let offset = 0;
	for (const part of parts) {
		combined.set(part, offset);
		offset += part.length;
	}

	return combined;
}

/**
 * Encodes Client struct to Uint8Array
 * @param {Client} client
 * @returns {Uint8Array}
 */
function encodeClient(client) {
	let kyPublicKeyBytes = encodeKyPublicKey(client.ky_p);
	let kySecretKeyBytes = encodeKySecretKey(client.ky_q);
	let diPublicKeyBytes = encodeDiPublicKey(client.di_p);
	let diSecretKeyBytes = encodeDiSecretKey(client.di_q);
	let secretBytes = client.secret ? client.secret : new Uint8Array(0);
	let secretPresentFlag = client.secret ? 1 : 0;

	let totalLength = 0;
	totalLength += kyPublicKeyBytes.length;
	totalLength += kySecretKeyBytes.length;
	totalLength += diPublicKeyBytes.length;
	totalLength += diSecretKeyBytes.length;
	totalLength += 1; // secret present flag
	if (secretPresentFlag) {
		totalLength += secretBytes.length; // secret bytes (always 32 if present)
	}

	const buffer = new ArrayBuffer(totalLength);
	const view = new Uint8Array(buffer);
	let offset = 0;

	view.set(kyPublicKeyBytes, offset);
	offset += kyPublicKeyBytes.length;
	view.set(kySecretKeyBytes, offset);
	offset += kySecretKeyBytes.length;
	view.set(diPublicKeyBytes, offset);
	offset += diPublicKeyBytes.length;
	view.set(diSecretKeyBytes, offset);
	offset += diSecretKeyBytes.length;
	view.setUint8(offset++, secretPresentFlag);
	if (secretPresentFlag) {
		view.set(secretBytes, offset);
		offset += secretBytes.length;
	}

	return new Uint8Array(buffer);
}

/**
 * Encodes string to Uint8Array, prepended with length
 * @param {string} str
 * @returns {Uint8Array}
 */
function encodeString(str) {
	const encoder = new TextEncoder();
	const encodedStr = encoder.encode(str);
	const len = encodedStr.length;

	const buffer = new ArrayBuffer(8 + len);
	const view = new DataView(buffer);
	let offset = 0;

	view.setBigUint64(offset, BigInt(len), true);
	offset += 8;
	for (let i = 0; i < len; ++i) {
		view.setUint8(offset++, encodedStr[i]);
	}
	return new Uint8Array(buffer);
}

/**
 * Encodes KySecretKey to Uint8Array, prepended with length (although length is fixed)
 * @param {KySecretKey} key
 * @returns {Uint8Array}
 */
function encodeKySecretKey(key) {
	const len = key.length;
	const buffer = new ArrayBuffer(8 + len);
	const view = new DataView(buffer);
	let offset = 0;

	view.setBigUint64(offset, BigInt(len), true);
	offset += 8;
	for (let i = 0; i < len; ++i) {
		view.setUint8(offset++, key[i]);
	}
	return new Uint8Array(buffer);
}

/**
 * Encodes DiSecretKey to Uint8Array, prepended with length (although length is fixed)
 * @param {DiSecretKey} key
 * @returns {Uint8Array}
 */
function encodeDiSecretKey(key) {
	const len = key.length;
	const buffer = new ArrayBuffer(8 + len);
	const view = new DataView(buffer);
	let offset = 0;

	view.setBigUint64(offset, BigInt(len), true);
	offset += 8;
	for (let i = 0; i < len; ++i) {
		view.setUint8(offset++, key[i]);
	}
	return new Uint8Array(buffer);
}

/**
 * Encodes DiPublicKey to Uint8Array, prepended with length (although length is fixed)
 * @param {DiPublicKey} key
 * @returns {Uint8Array}
 */
function encodeDiPublicKey(key) {
	const len = key.length;
	const buffer = new ArrayBuffer(8 + len);
	const view = new DataView(buffer);
	let offset = 0;

	view.setBigUint64(offset, BigInt(len), true);
	offset += 8;
	for (let i = 0; i < len; ++i) {
		view.setUint8(offset++, key[i]);
	}
	return new Uint8Array(buffer);
}

/**
 * Encodes KyPublicKey to Uint8Array, prepended with length (although length is fixed)
 * @param {KyPublicKey} key
 * @returns {Uint8Array}
 */
function encodeKyPublicKey(key) {
	const len = key.length;
	const buffer = new ArrayBuffer(8 + len);
	const view = new DataView(buffer);
	let offset = 0;

	view.setBigUint64(offset, BigInt(len), true);
	offset += 8;
	for (let i = 0; i < len; ++i) {
		view.setUint8(offset++, key[i]);
	}
	return new Uint8Array(buffer);
}

/**
 * Decodes a Password object from a Uint8Array of bytes.
 *
 * @param {Uint8Array} bytes - The byte array to decode.
 * @returns {Password|null} - An object containing the decoded Password and remaining bytes, or null if decoding fails.
 */
export function decodePassword(bytes) {
	let offset = 0;

	if (!bytes || !(bytes instanceof Uint8Array)) {
		console.error('decodePassword: Input is not a valid Uint8Array.');
		return null;
	}

	const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

	function readString(bytes, offset) {
		if (!bytes || !(bytes instanceof Uint8Array)) {
			console.error('decodeString: Input is not a valid Uint8Array.');
			return null;
		}
		const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

		const len = Number(view.getBigUint64(offset, true));
		offset += 8;
		if (offset + len > bytes.byteLength) return null; // MalformedData or EndOfStream

		const strBytes = bytes.slice(offset, offset + len);
		const decoder = new TextDecoder();
		const str = decoder.decode(strBytes);
		return { str: str, bytesRead: 8 + len };
	}

	// Decode password
	let passwordResult = readString(bytes, offset);
	if (!passwordResult) return null;
	let password = passwordResult.str;
	offset += passwordResult.bytesRead;

	// Decode app_id
	const appIdPresent = view.getUint8(offset);

	let app_id = null;
	if (appIdPresent === 1) {
		offset++;
		let appResult = readString(bytes, offset);
		if (!appResult) return null;
		app_id = appResult.str;
		offset += appResult.bytesRead;
	} else if (appIdPresent !== 0) {
		return null; // MalformedData
	} else {
		offset++;
	}

	// Decode username
	let usernameResult = readString(bytes, offset);

	if (!usernameResult) return null;
	let username = usernameResult.str;
	offset += usernameResult.bytesRead;
	// Decode description
	const descriptionPresent = view.getUint8(offset);
	let description = null;
	if (descriptionPresent === 1) {
		offset++;
		let descriptionResult = readString(bytes, offset);
		if (!descriptionResult) return null;
		description = descriptionResult.str;
		offset += descriptionResult.bytesRead;
	} else if (descriptionPresent !== 0) {
		return null; // MalformedData
	} else {
		offset++;
	}

	// Decode url
	const urlPresent = view.getUint8(offset);

	let url = null;
	if (urlPresent === 1) {
		offset++;
		let urlResult = readString(bytes, offset);
		if (!urlResult) return null;
		url = urlResult.str;
		offset += urlResult.bytesRead;
	} else if (urlPresent !== 0) {
		return null; // MalformedData
	} else {
		offset++;
	}

	// Decode otp
	const otpPresent = view.getUint8(offset);
	let otp = null;
	if (otpPresent === 1) {
		offset++;
		let otpResult = readString(bytes, offset);
		if (!otpResult) return null;
		otp = otpResult.str;
		offset += otpResult.bytesRead;
	} else if (otpPresent !== 0) {
		return null; // MalformedData
	} else {
		offset++;
	}

	const passwordObject = {
		password: password,
		app_id: app_id,
		username: username,
		description: description,
		url: url,
		otp: otp
	};

	return passwordObject;
}

/**
 * Decodes EP struct from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {EP|null}
 */
export function decodeEP(bytes) {
	if (!bytes || !(bytes instanceof Uint8Array)) {
		console.error('decodeString: Input is not a valid Uint8Array.');
		return null;
	}
	const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	let offset = 0;

	const ciphertextLen = Number(view.getBigUint64(offset, true));
	offset += 8;
	if (offset + ciphertextLen > bytes.byteLength) return null; // MalformedData or EndOfStream
	const ciphertext = bytes.slice(offset, offset + ciphertextLen);
	offset += ciphertextLen;

	const nonceLen = Number(view.getBigUint64(offset, true));
	offset += 8;
	if (offset + nonceLen > bytes.byteLength) return null; // MalformedData or EndOfStream
	const nonce = bytes.slice(offset, offset + nonceLen);
	offset += nonceLen;

	const nonce2PresentFlag = view.getUint8(offset++);
	let nonce2 = null;
	if (nonce2PresentFlag === 1) {
		const nonce2Len = Number(view.getBigUint64(offset, true));
		offset += 8;
		if (offset + nonce2Len > bytes.byteLength) return null; // MalformedData or EndOfStream
		nonce2 = bytes.slice(offset, offset + nonce2Len);
		offset += nonce2Len;
	} else if (nonce2PresentFlag !== 0) {
		return null; // MalformedData
	}

	return { ciphertext: ciphertext, nonce: nonce, nonce2: nonce2 };
}

/**
 * Decodes ClientEx struct from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {ClientEx|null}
 */
export function decodeClientEx(bytes) {
	let clientResult = decodeClient(bytes);
	if (!clientResult) return null;
	let client = clientResult.client;
	let remainingBytesAfterClient = clientResult.remainingBytes;

	let ckResult = decodeCK(remainingBytesAfterClient);
	if (!ckResult) return null;
	let ck = ckResult.ck;

	return { c: client, id: ck };
}

/**
 * Decodes Client struct from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {{client: Client|null, remainingBytes: Uint8Array|null}}
 */
function decodeClient(bytes) {
	let offset = 0;

	// Skip 8 bytes lengths for fixed size arrays in Zig's decodeClient
	offset += 8; // skip ky_p length
	let ky_p_result = decodeKyPublicKey(bytes.slice(offset));
	if (!ky_p_result) return null;
	let ky_p = ky_p_result.key;
	offset += KyPublicKeySize;

	offset += 8; // skip ky_q length
	let ky_q_result = decodeKySecretKey(bytes.slice(offset));
	if (!ky_q_result) return null;
	let ky_q = ky_q_result.key;
	offset += KySecretKeySize;

	offset += 8; // skip di_p length
	let di_p_result = decodeDiPublicKey(bytes.slice(offset));
	if (!di_p_result) return null;
	let di_p = di_p_result.key;
	offset += DiPublicKeySize;

	offset += 8; // skip di_q length
	let di_q_result = decodeDiSecretKey(bytes.slice(offset));
	if (!di_q_result) return null;
	let di_q = di_q_result.key;
	offset += DiSecretKeySize;

	if (!bytes || !(bytes instanceof Uint8Array)) {
		console.error('decodeString: Input is not a valid Uint8Array.');
		console.log(typeof bytes);
		return null;
	}
	const view = new DataView(bytes.buffer, bytes.byteOffset + offset, bytes.byteLength - offset);
	offset = 0; // reset offset for DataView relative to remaining bytes

	const secretPresent = view.getUint8(offset++);
	let secret = null;
	if (secretPresent === 1) {
		if (offset + 32 > view.byteLength) return null; // MalformedData
		secret = bytes.slice(view.byteOffset + offset, view.byteOffset + offset + 32);
		offset += 32;
	} else if (secretPresent !== 0) {
		return null; // MalformedData
	}

	const client = {
		ky_p: ky_p,
		ky_q: ky_q,
		di_p: di_p,
		di_q: di_q,
		secret: secret
	};
	return {
		client: client,
		remainingBytes: bytes.slice(view.byteOffset + offset)
	};
}

/**
 * Decodes CK struct from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {{ck: CK|null, remainingBytes: Uint8Array|null}}
 */
function decodeCK(bytes) {
	let offset = 0;

	let emailResult = decodeString(bytes);
	if (!emailResult) return null;
	let email = emailResult.str;
	offset += emailResult.bytesRead;

	const view = new DataView(bytes.buffer, bytes.byteOffset + offset, bytes.byteLength - offset);
	offset = 0; // reset offset for DataView relative to remaining bytes

	const uuidPresent = view.getUint8(offset++);
	let uuid = null;
	if (uuidPresent === 1) {
		offset += 8; // skip uuid length
		let uuidBytes = bytes.slice(view.byteOffset + offset, view.byteOffset + offset + 16);
		if (uuidBytes.length !== 16) return null; // MalformedData
		uuid = { bytes: uuidBytes };
		offset += 16;
	} else if (uuidPresent !== 0) {
		return null; // MalformedData
	}

	offset += 8; // skip ky_p length
	let ky_p_result = decodeKyPublicKey(bytes.slice(view.byteOffset + offset));
	if (!ky_p_result) return null;
	let ky_p = ky_p_result.key;
	offset += KyPublicKeySize;

	offset += 8; // skip di_p length
	let di_p_result = decodeDiPublicKey(bytes.slice(view.byteOffset + offset));
	if (!di_p_result) return null;
	let di_p = di_p_result.key;
	offset += DiPublicKeySize;

	const ck = { email: email, id: uuid, ky_p: ky_p, di_p: di_p };
	return { ck: ck, remainingBytes: bytes.slice(view.byteOffset + offset) };
}

/**
 * Decodes string from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {{str: string|null, bytesRead: number}}
 */
function decodeString(bytes) {
	if (!bytes || !(bytes instanceof Uint8Array)) {
		console.error('decodeString: Input is not a valid Uint8Array.');
		return null;
	}
	const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	let offset = 0;

	const len = Number(view.getBigUint64(offset, true));
	offset += 8;
	if (offset + len > bytes.byteLength) return null; // MalformedData or EndOfStream

	const strBytes = bytes.slice(offset, offset + len);
	const decoder = new TextDecoder();
	const str = decoder.decode(strBytes);
	return { str: str, bytesRead: 8 + len };
}

/**
 * Decodes KyPublicKey from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {{key: KyPublicKey|null}}
 */
function decodeKyPublicKey(bytes) {
	if (bytes.length < KyPublicKeySize) return null; // MalformedData or EndOfStream
	const key = bytes.slice(0, KyPublicKeySize);
	return { key: key };
}

/**
 * Decodes DiPublicKey from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {{key: DiPublicKey|null}}
 */
function decodeDiPublicKey(bytes) {
	if (bytes.length < DiPublicKeySize) return null; // MalformedData or EndOfStream
	const key = bytes.slice(0, DiPublicKeySize);
	return { key: key };
}

/**
 * Decodes KySecretKey from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {{key: KySecretKey|null}}
 */
function decodeKySecretKey(bytes) {
	if (bytes.length < KySecretKeySize) return null; // MalformedData or EndOfStream
	const key = bytes.slice(0, KySecretKeySize);
	return { key: key };
}

/**
 * Decodes DiSecretKey from Uint8Array
 * @param {Uint8Array} bytes
 * @returns {{key: DiSecretKey|null}}
 */
function decodeDiSecretKey(bytes) {
	if (bytes.length < DiSecretKeySize) return null; // MalformedData or EndOfStream
	const key = bytes.slice(0, DiSecretKeySize);
	return { key: key };
}

/**
 * Converts Uuid to string representation
 * @param {Uuid} uuid
 * @returns {string}
 */
export function uuidToStr(uuid) {
	const bytes = uuid.bytes;
	const uuidstring = toHexString(bytes);
	const uuids = uuidstring.replace(/(.{8})(.{5})(.{5})(.{5})(.{12})/g, '$1-$2-$3-$4-$5');
	return uuids;
}

const toHexString = (bytes) =>
	bytes.reduce((str, byte) => str + byte.toString(16).padStart(2, '0'), '');

/**
 * Encodes a CK object to a Uint8Array
 * @param {CK} ck - The CK object to encode
 * @returns {Uint8Array} - The encoded CK as a Uint8Array
 */
export function encodeCK(ck) {
	if (!ck || !ck.email || !ck.id || !ck.ky_p || !ck.di_p) {
		throw new Error('Invalid CK object');
	}

	const encoder = new TextEncoder();
	const emailBytes = encoder.encode(ck.email);
	const uuidBytes = ck.id.bytes;
	const ky_pBytes = ck.ky_p;
	const di_pBytes = ck.di_p;

	const totalSize = 8 + emailBytes.length + 16 + ky_pBytes.length + di_pBytes.length;

	const buffer = new ArrayBuffer(totalSize);
	const view = new DataView(buffer);
	let offset = 0;

	// Write email
	view.setBigUint64(offset, BigInt(emailBytes.length), true);
	offset += 8;

	const uint8Array = new Uint8Array(buffer);
	uint8Array.set(emailBytes, offset);
	offset += emailBytes.length;

	// Write UUID
	view.setUint8(offset, 1); // UUID present
	offset += 1;

	view.setBigUint64(offset, BigInt(16), true); // UUID length
	offset += 8;

	uint8Array.set(uuidBytes, offset);
	offset += 16;

	// Write ky_p
	view.setBigUint64(offset, BigInt(ky_pBytes.length), true);
	offset += 8;

	uint8Array.set(ky_pBytes, offset);
	offset += ky_pBytes.length;

	// Write di_p
	view.setBigUint64(offset, BigInt(di_pBytes.length), true);
	offset += 8;

	uint8Array.set(di_pBytes, offset);
	offset += di_pBytes.length;

	return uint8Array;
}

/**
 * Encodes a ClientEx object to a Uint8Array
 * @param {ClientEx} clientEx - The ClientEx object to encode
 * @returns {Promise<Uint8Array>} - The encoded ClientEx as a Uint8Array
 */
export async function encodeClientEx(clientEx) {
	if (!clientEx || !clientEx.c || !clientEx.id) {
		throw new Error('Invalid ClientEx object');
	}

	// Encode email
	const encoder = new TextEncoder();
	const emailBytes = encoder.encode(clientEx.id.email || '');

	// Calculate total size
	let totalSize = 0;
	totalSize += 8; // email length
	totalSize += emailBytes.length;

	// UUID
	totalSize += 1; // UUID present flag
	if (clientEx.id.id) {
		totalSize += 8; // UUID length
		totalSize += 16; // UUID bytes
	}

	// Public keys
	totalSize += 8; // ky_p length
	totalSize += clientEx.id.ky_p.length;

	totalSize += 8; // di_p length
	totalSize += clientEx.id.di_p.length;

	// Client
	totalSize += 8; // ky_p length
	totalSize += clientEx.c.ky_p.length;

	totalSize += 8; // ky_q length
	totalSize += clientEx.c.ky_q.length;

	totalSize += 8; // di_p length
	totalSize += clientEx.c.di_p.length;

	totalSize += 8; // di_q length
	totalSize += clientEx.c.di_q.length;

	totalSize += 1; // secret present flag
	if (clientEx.c.secret) {
		totalSize += 8; // secret length
		totalSize += clientEx.c.secret.length;
	}

	// Create buffer
	const buffer = new ArrayBuffer(totalSize);
	const view = new DataView(buffer);
	let offset = 0;

	// Write email
	view.setBigUint64(offset, BigInt(emailBytes.length), true);
	offset += 8;

	const uint8Array = new Uint8Array(buffer);
	uint8Array.set(emailBytes, offset);
	offset += emailBytes.length;

	// Write UUID
	if (clientEx.id.id) {
		view.setUint8(offset, 1); // UUID present
		offset += 1;

		view.setBigUint64(offset, BigInt(16), true); // UUID length
		offset += 8;

		uint8Array.set(clientEx.id.id.bytes, offset);
		offset += 16;
	} else {
		view.setUint8(offset, 0); // UUID not present
		offset += 1;
	}

	// Write public keys for id
	view.setBigUint64(offset, BigInt(clientEx.id.ky_p.length), true);
	offset += 8;
	uint8Array.set(clientEx.id.ky_p, offset);
	offset += clientEx.id.ky_p.length;

	view.setBigUint64(offset, BigInt(clientEx.id.di_p.length), true);
	offset += 8;
	uint8Array.set(clientEx.id.di_p, offset);
	offset += clientEx.id.di_p.length;

	// Write client keys
	view.setBigUint64(offset, BigInt(clientEx.c.ky_p.length), true);
	offset += 8;
	uint8Array.set(clientEx.c.ky_p, offset);
	offset += clientEx.c.ky_p.length;

	view.setBigUint64(offset, BigInt(clientEx.c.ky_q.length), true);
	offset += 8;
	uint8Array.set(clientEx.c.ky_q, offset);
	offset += clientEx.c.ky_q.length;

	view.setBigUint64(offset, BigInt(clientEx.c.di_p.length), true);
	offset += 8;
	uint8Array.set(clientEx.c.di_p, offset);
	offset += clientEx.c.di_p.length;

	view.setBigUint64(offset, BigInt(clientEx.c.di_q.length), true);
	offset += 8;
	uint8Array.set(clientEx.c.di_q, offset);
	offset += clientEx.c.di_q.length;

	// Write secret
	if (clientEx.c.secret) {
		view.setUint8(offset, 1); // secret present
		offset += 1;

		view.setBigUint64(offset, BigInt(clientEx.c.secret.length), true);
		offset += 8;

		uint8Array.set(clientEx.c.secret, offset);
		offset += clientEx.c.secret.length;
	} else {
		view.setUint8(offset, 0); // secret not present
		offset += 1;
	}

	return uint8Array;
}
