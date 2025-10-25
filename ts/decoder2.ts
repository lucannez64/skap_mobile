// Constants pour les tailles des clés
const KYBER_SSBYTES = 32;
const KYBER_PUBLICKEYBYTES = 1568;
const KYBER_SECRETKEYBYTES = 3168;
const KYBER_CIPHERTEXTBYTES = 1568;
const DILITHIUM_PUBLICKEYBYTES = 2592;
const DILITHIUM_SECRETKEYBYTES = 4896;

// Interfaces pour les types
export interface KyPublicKey {
	data: Uint8Array; // [u8; KYBER_PUBLICKEYBYTES]
}

export interface KySecretKey {
	data: Uint8Array; // [u8; KYBER_SECRETKEYBYTES]
}

export interface DiPublicKey {
	data: Uint8Array; // [u8; DILITHIUM_PUBLICKEYBYTES]
}

export interface DiSecretKey {
	data: Uint8Array; // [u8; DILITHIUM_SECRETKEYBYTES]
}

export interface Uuid {
	bytes: Uint8Array; // [u8; 16]
}

export interface CK {
	email: string;
	id: Uuid | null;
	ky_p: KyPublicKey;
	di_p: DiPublicKey;
}

export interface Client {
	ky_p: KyPublicKey;
	ky_q: KySecretKey;
	di_p: DiPublicKey;
	di_q: DiSecretKey;
	secret: Uint8Array | null; // Option<[u8; KYBER_SSBYTES]>
}

export interface ClientEx {
	c: Client;
	id: CK;
}

/**
 * Classe pour encoder les données selon la spécification bincode
 */
export class BincodeEncoder {
	private buffer: Uint8Array;
	private offset: number;

	constructor(size: number = 16384) {
		this.buffer = new Uint8Array(size);
		this.offset = 0;
	}

	/**
	 * Redimensionne le buffer si nécessaire
	 */
	private ensureCapacity(additionalBytes: number): void {
		if (this.offset + additionalBytes > this.buffer.length) {
			const newBuffer = new Uint8Array(
				Math.max(this.buffer.length * 2, this.offset + additionalBytes)
			);
			newBuffer.set(this.buffer);
			this.buffer = newBuffer;
		}
	}

	/**
	 * Écrit un uint64 en little endian
	 */
	private writeUint64(value: bigint): void {
		this.ensureCapacity(8);
		const view = new DataView(this.buffer.buffer, this.offset, 8);
		view.setBigUint64(0, value, true); // true pour little endian
		this.offset += 8;
	}

	/**
	 * Encode un uint8
	 */
	private encodeU8(value: number): void {
		this.ensureCapacity(1);
		this.buffer[this.offset++] = value & 0xff;
	}

	/**
	 * Encode un tableau d'octets de taille fixe avec sa longueur
	 * Pour être compatible avec le décodeur JavaScript
	 */
	private encodeFixedBytesWithLength(bytes: Uint8Array): void {
		// Écrire la longueur en tant que uint64
		this.writeUint64(BigInt(bytes.length));

		// Écrire les octets
		this.ensureCapacity(bytes.length);
		this.buffer.set(bytes, this.offset);
		this.offset += bytes.length;
	}

	/**
	 * Encode une chaîne de caractères
	 */
	private encodeString(value: string): void {
		const encoder = new TextEncoder();
		const bytes = encoder.encode(value);

		// Écrire la longueur en tant que uint64
		this.writeUint64(BigInt(bytes.length));

		// Écrire les octets de la chaîne
		this.ensureCapacity(bytes.length);
		this.buffer.set(bytes, this.offset);
		this.offset += bytes.length;
	}

	/**
	 * Encode une option (Some ou None)
	 */
	private encodeOption<T>(value: T | null, encoder: (val: T) => void): void {
		if (value === null) {
			this.encodeU8(0); // None
		} else {
			this.encodeU8(1); // Some
			encoder(value);
		}
	}

	/**
	 * Encode un UUID
	 */
	private encodeUuid(uuid: Uuid): void {
		// Pour les UUID, on écrit d'abord la longueur (toujours 16)
		this.writeUint64(BigInt(16));

		// Puis les octets
		this.ensureCapacity(16);
		this.buffer.set(uuid.bytes, this.offset);
		this.offset += 16;
	}

	/**
	 * Encode une clé publique Kyber
	 */
	private encodeKyPublicKey(key: KyPublicKey): void {
		this.encodeFixedBytesWithLength(key.data);
	}

	/**
	 * Encode une clé secrète Kyber
	 */
	private encodeKySecretKey(key: KySecretKey): void {
		this.encodeFixedBytesWithLength(key.data);
	}

	/**
	 * Encode une clé publique Dilithium
	 */
	private encodeDiPublicKey(key: DiPublicKey): void {
		this.encodeFixedBytesWithLength(key.data);
	}

	/**
	 * Encode une clé secrète Dilithium
	 */
	private encodeDiSecretKey(key: DiSecretKey): void {
		this.encodeFixedBytesWithLength(key.data);
	}

	/**
	 * Encode un CK
	 */
	private encodeCK(ck: CK): void {
		// Encode email
		this.encodeString(ck.email);

		// Encode id (Option<Uuid>)
		this.encodeOption(ck.id, (id) => this.encodeUuid(id));

		// Encode ky_p
		this.encodeKyPublicKey(ck.ky_p);

		// Encode di_p
		this.encodeDiPublicKey(ck.di_p);
	}

	/**
	 * Encode un Client
	 */
	private encodeClient(client: Client): void {
		// Encode ky_p
		this.encodeKyPublicKey(client.ky_p);

		// Encode ky_q
		this.encodeKySecretKey(client.ky_q);

		// Encode di_p
		this.encodeDiPublicKey(client.di_p);

		// Encode di_q
		this.encodeDiSecretKey(client.di_q);

		// Encode secret (Option<[u8; KYBER_SSBYTES]>)
		if (client.secret) {
			this.encodeU8(1); // Some
			// Pas besoin d'encoder la longueur pour le secret car c'est une taille fixe
			this.ensureCapacity(client.secret.length);
			this.buffer.set(client.secret, this.offset);
			this.offset += client.secret.length;
		} else {
			this.encodeU8(0); // None
		}
	}

	/**
	 * Encode un ClientEx
	 */
	public encodeClientEx(clientEx: ClientEx): Uint8Array {
		// Réinitialiser l'offset
		this.offset = 0;

		// Encode Client
		this.encodeClient(clientEx.c);

		// Encode CK
		this.encodeCK(clientEx.id);

		// Retourner le buffer avec la taille exacte
		return this.buffer.slice(0, this.offset);
	}

	/**
	 * Crée un KyPublicKey à partir d'un tableau d'octets
	 */
	public static createKyPublicKey(data: Uint8Array): KyPublicKey {
		if (data.length !== KYBER_PUBLICKEYBYTES) {
			throw new Error(`KyPublicKey doit avoir exactement ${KYBER_PUBLICKEYBYTES} octets`);
		}
		return { data };
	}

	/**
	 * Crée un KySecretKey à partir d'un tableau d'octets
	 */
	public static createKySecretKey(data: Uint8Array): KySecretKey {
		if (data.length !== KYBER_SECRETKEYBYTES) {
			throw new Error(`KySecretKey doit avoir exactement ${KYBER_SECRETKEYBYTES} octets`);
		}
		return { data };
	}

	/**
	 * Crée un DiPublicKey à partir d'un tableau d'octets
	 */
	public static createDiPublicKey(data: Uint8Array): DiPublicKey {
		if (data.length !== DILITHIUM_PUBLICKEYBYTES) {
			throw new Error(`DiPublicKey doit avoir exactement ${DILITHIUM_PUBLICKEYBYTES} octets`);
		}
		return { data };
	}

	/**
	 * Crée un DiSecretKey à partir d'un tableau d'octets
	 */
	public static createDiSecretKey(data: Uint8Array): DiSecretKey {
		if (data.length !== DILITHIUM_SECRETKEYBYTES) {
			throw new Error(`DiSecretKey doit avoir exactement ${DILITHIUM_SECRETKEYBYTES} octets`);
		}
		return { data };
	}

	/**
	 * Crée un UUID à partir d'un tableau d'octets ou d'une chaîne
	 */
	public static createUuid(input: Uint8Array | string): Uuid {
		if (typeof input === 'string') {
			// Convertir la chaîne UUID en tableau d'octets
			const hex = input.replace(/-/g, '');
			const bytes = new Uint8Array(16);

			for (let i = 0; i < 16; i++) {
				bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
			}

			return { bytes };
		} else {
			if (input.length !== 16) {
				throw new Error('UUID doit avoir exactement 16 octets');
			}
			return { bytes: input };
		}
	}
}

// Exemple d'utilisation
export function createExampleClientEx(): ClientEx {
	// Créer des données aléatoires pour les clés
	const kyPubData = new Uint8Array(KYBER_PUBLICKEYBYTES);
	const kySecData = new Uint8Array(KYBER_SECRETKEYBYTES);
	const diPubData = new Uint8Array(DILITHIUM_PUBLICKEYBYTES);
	const diSecData = new Uint8Array(DILITHIUM_SECRETKEYBYTES);
	const secretData = new Uint8Array(KYBER_SSBYTES);
	const uuidData = new Uint8Array(16);

	// Remplir avec des données aléatoires
	crypto.getRandomValues(kyPubData);
	crypto.getRandomValues(kySecData);
	crypto.getRandomValues(diPubData);
	crypto.getRandomValues(diSecData);
	crypto.getRandomValues(secretData);
	crypto.getRandomValues(uuidData);

	// Créer les objets
	const ky_p = BincodeEncoder.createKyPublicKey(kyPubData);
	const ky_q = BincodeEncoder.createKySecretKey(kySecData);
	const di_p = BincodeEncoder.createDiPublicKey(diPubData);
	const di_q = BincodeEncoder.createDiSecretKey(diSecData);
	const uuid = BincodeEncoder.createUuid(uuidData);

	// Créer le Client
	const client: Client = {
		ky_p,
		ky_q,
		di_p,
		di_q,
		secret: secretData
	};

	// Créer le CK
	const ck: CK = {
		email: 'exemple@email.com',
		id: uuid,
		ky_p,
		di_p
	};

	// Créer et retourner le ClientEx
	return {
		c: client,
		id: ck
	};
}

// Exemple d'encodage
export function encodeExampleClientEx(): Uint8Array {
	const clientEx = createExampleClientEx();
	console.log(clientEx);
	const encoder = new BincodeEncoder();
	return encoder.encodeClientEx(clientEx);
}
