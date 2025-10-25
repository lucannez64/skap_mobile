import {
	type Client,
	decodePassword,
	encodePassword,
	type EP,
	type Password,
	type Uuid,
	uuidToStr,
	type ClientEx,
	type CK,
	type KyPublicKey,
	type DiPublicKey
} from './decoder.js';
import {
	BincodeEncoder,
	type ClientEx as ClientEx2,
	type Client as Client2,
	encodeExampleClientEx
} from './decoder2';
import { ml_dsa87 } from '@noble/post-quantum/ml-dsa';
import { ml_kem1024 } from '@noble/post-quantum/ml-kem';
import { blake3 } from '@noble/hashes/blake3';
import { xchacha20poly1305 } from '@noble/ciphers/chacha';
import { randomBytes } from '@noble/ciphers/webcrypto';
import * as pkg from 'uuid-tool';
const { Uuid } = pkg;

// Utiliser une URL relative pour que le proxy Vite fonctionne correctement
const API_URL = '/api/';

// Cache pour stocker les résultats des requêtes API
const apiCache = new Map<string, { data: any; timestamp: number }>();
const CACHE_EXPIRATION = 60000; // 1 minute en millisecondes

// Stockage des fonctions debounce
interface DebouncedFunction {
	timeout: NodeJS.Timeout | null;
	lastCall: number;
}
const debouncedFunctions = new Map<string, DebouncedFunction>();

/**
 * Debounce une fonction pour limiter sa fréquence d'appel
 * @param fn Fonction à debouncer
 * @param delay Délai en ms
 * @param key Clé unique pour identifier la fonction
 * @returns Fonction debounced
 */
function debounce<T extends (...args: any[]) => any>(
	fn: T,
	delay: number,
	key: string
): (...args: Parameters<T>) => Promise<ReturnType<T>> {
	return (...args: Parameters<T>): Promise<ReturnType<T>> => {
		return new Promise((resolve) => {
			if (!debouncedFunctions.has(key)) {
				debouncedFunctions.set(key, { timeout: null, lastCall: 0 });
			}

			const debouncedFn = debouncedFunctions.get(key)!;
			const now = Date.now();
			const elapsed = now - debouncedFn.lastCall;

			if (debouncedFn.timeout) {
				clearTimeout(debouncedFn.timeout);
			}

			if (elapsed > delay) {
				// Exécuter immédiatement si assez de temps s'est écoulé
				debouncedFn.lastCall = now;
				resolve(fn(...args) as ReturnType<T>);
			} else {
				// Sinon attendre le délai
				debouncedFn.timeout = setTimeout(() => {
					debouncedFn.lastCall = Date.now();
					resolve(fn(...args) as ReturnType<T>);
				}, delay);
			}
		});
	};
}

/**
 * Effectue une requête fetch avec mise en cache
 * @param url URL de la requête
 * @param options Options fetch
 * @param cacheKey Clé de cache (si non spécifiée, l'URL sera utilisée)
 * @param cacheDuration Durée de validité du cache en ms (défaut: CACHE_EXPIRATION)
 * @returns Résultat de la requête
 */
async function cachedFetch(
	url: string,
	options?: RequestInit,
	cacheKey?: string,
	cacheDuration?: number
): Promise<Response> {
	const key = cacheKey || url;
	const now = Date.now();

	// Vérifier si la donnée est en cache et toujours valide
	if (apiCache.has(key)) {
		const cachedData = apiCache.get(key)!;
		if (now - cachedData.timestamp < (cacheDuration || CACHE_EXPIRATION)) {
			// Retourner les données du cache
			return new Response(JSON.stringify(cachedData.data), {
				headers: { 'Content-Type': 'application/json' },
				status: 200
			});
		}
	}

	// Effectuer la requête réelle
	const response = await fetch(url, options);

	// Si la requête a réussi et qu'il s'agit d'une requête GET, mettre en cache
	if (response.ok && (!options || options.method === undefined || options.method === 'GET')) {
		try {
			const clonedResponse = response.clone();
			const data = await clonedResponse.json();
			apiCache.set(key, {
				data,
				timestamp: now
			});
		} catch (error) {
			console.warn('Impossible de mettre en cache la réponse:', error);
		}
	}

	return response;
}

/**
 * Invalide une entrée du cache
 * @param cacheKey Clé à invalider
 */
function invalidateCache(cacheKey: string) {
	apiCache.delete(cacheKey);
}

/**
 * Invalide toutes les entrées du cache
 */
function clearCache() {
	apiCache.clear();
}

/**
 * Structure pour un mot de passe partagé
 */
interface SharedPass {
	kem_ct: Uint8Array;
	ep: EP;
	status: ShareStatus;
}

export enum ShareStatus {
	Pending,
	Accepted,
	Rejected
}

interface SharedByUser {
	pass_id: Uuid;
	recipient_ids: Uuid[];
}

export interface SharedByUserEmail {
	pass_id: Uuid;
	emails: string[];
	statuses?: ShareStatus[];
}

interface ReceivedCK {
	email: string;
	id: string | null;
	ky_p: { bytes: Uint8Array };
	di_p: { bytes: Uint8Array };
}

/**
 * Crée un nouveau compte utilisateur
 * @param email Email de l'utilisateur
 * @returns Un objet contenant le ClientEx généré et un fichier à télécharger, ou une erreur
 */
export async function create_account(email: string) {
	try {
		// Générer les clés pour le client
		const ky_p = ml_kem1024.keygen();
		const { publicKey: di_p, secretKey: di_q } = ml_dsa87.keygen(randomBytes(32));
		const secret = randomBytes(32);

		// Créer un UUID pour l'utilisateur
		// Créer le client
		const client: Client2 = {
			ky_p: { data: ky_p.publicKey },
			ky_q: { data: ky_p.secretKey },
			di_p: { data: di_p },
			di_q: { data: di_q },
			secret
		};

		const client2: Client = {
			ky_p: ky_p.publicKey,
			ky_q: ky_p.secretKey,
			di_p: di_p,
			di_q: di_q,
			secret
		};

		// Créer le ClientEx
		const clientEx: ClientEx2 = {
			c: client,
			id: {
				email,
				id: null,
				ky_p: { data: ky_p.publicKey },
				di_p: { data: di_p }
			}
		};

		// Encoder le ClientEx pour le téléchargement

		// Créer l'objet CK selon le format attendu par le serveur
		// Notez que dans le modèle Rust, id est Option<Uuid>, donc nous pouvons l'envoyer directement
		const cK = {
			email,
			id: null,
			ky_p: { bytes: Array.from(ky_p.publicKey) },
			di_p: { bytes: Array.from(di_p) }
		};

		// Enregistrer l'utilisateur sur le serveur
		const response = await fetch(API_URL + 'create_user_json/', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json'
			},
			body: JSON.stringify(cK)
		});
		const encoder = new BincodeEncoder();

		const r: ReceivedCK = await response.json();
		const clientEx2: ClientEx2 = {
			c: client,
			id: {
				email,
				id: BincodeEncoder.createUuid(r.id!),
				ky_p: { data: r.ky_p.bytes },
				di_p: { data: r.di_p.bytes }
			}
		};

		const clientEx3: ClientEx = {
			c: client2,
			id: {
				email,
				id: { bytes: BincodeEncoder.createUuid(r.id!).bytes },
				ky_p: r.ky_p.bytes,
				di_p: r.di_p.bytes
			}
		};

		if (!response.ok) {
			return {
				clientEx: null,
				encodedFile: null,
				error: `Erreur lors de l'enregistrement: ${response.statusText}`
			};
		}

		const encodedClientEx = encoder.encodeClientEx(clientEx2);
		return {
			clientEx: clientEx3,
			encodedFile: encodedClientEx,
			error: null
		};
	} catch (error) {
		return {
			clientEx: null,
			encodedFile: null,
			error: `Erreur lors de la création du compte: ${error instanceof Error ? error.message : String(error)}`
		};
	}
}

export async function auth(uuid: Uuid, client: Client) {
	const response = await fetch(API_URL + 'challenge_json/' + uuidToStr(uuid));
	if (!response.ok) {
		return { result: null, client: null, error: response.statusText };
	}
	const challenge = await response.json();

	const challengeBytes = Uint8Array.from(challenge);
	const signature = ml_dsa87.sign(client.di_q, challengeBytes);
	const signArray = Array.from(signature);
	const response2 = await fetch(API_URL + 'verify_json/' + uuidToStr(uuid), {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json'
		},
		body: JSON.stringify(signArray)
	});
	if (!response2.ok) {
		return { result: null, client: null, error: response2.statusText };
	}
	const response3 = await fetch(API_URL + 'sync_json/' + uuidToStr(uuid));
	if (!response3.ok) {
		return { result: null, shared: null, error: response3.statusText };
	}
	const result2 = Uint8Array.from(await response3.json());
	const shared = ml_kem1024.decapsulate(result2, client.ky_q);
	client.secret = shared;
	return { result: response2, client: client, error: null };
}

export async function get_all(
	uuid: Uuid,
	client: Client
): Promise<{
	result: [Password[], Uuid[]] | null;
	shared: [Password[], Uuid[], Uuid[], ShareStatus[]] | null;
	error: string | null;
}> {
	const cacheKey = `get_all_${uuidToStr(uuid)}`;

	const response = await cachedFetch(
		API_URL + 'send_all_json/' + uuidToStr(uuid),
		undefined,
		cacheKey
	);
	if (!response.ok) {
		return { result: null, shared: null, error: response.statusText };
	}
	const result = await response.json();
	const secretKey = client.secret;
	const passwordsResult: [EP, string][] = result.passwords;
	console.log(passwordsResult);
	console.log(result.shared_passes);
	const passwordsPromises = passwordsResult.map(async (item: [EP, string]) => {
		const [ep, uuid] = item;
		const { result: decrypted, error } = decryptCredential(ep, secretKey, client.ky_q);
		if (error) {
			console.error(error);
		}
		const uuid2 = new Uuid(uuid);
		const uuid3 = {
			bytes: new Uint8Array(uuid2.toBytes())
		};
		return [decrypted, uuid3];
	});
	const passwords = await Promise.all(passwordsPromises);

	const sharedPasswordsResult: [SharedPass, string, string][] = result.shared_passes;
	const sharedPasswordsPromises = sharedPasswordsResult.map(
		async (item: [SharedPass, string, string]) => {
			const [sharedPass, ownerUuid, passUuid] = item;
			const uuid2 = new Uuid(ownerUuid);
			const uuid3: Uuid = {
				bytes: new Uint8Array(uuid2.toBytes())
			};
			const uuid4 = new Uuid(passUuid);
			const uuid5: Uuid = {
				bytes: new Uint8Array(uuid4.toBytes())
			};
			const sharedPassObj: SharedPass = {
				kem_ct: Uint8Array.from(sharedPass.kem_ct),
				ep: {
					ciphertext: Uint8Array.from(sharedPass.ep.ciphertext),
					nonce: Uint8Array.from(sharedPass.ep.nonce),
					nonce2: sharedPass.ep.nonce2 ? Uint8Array.from(sharedPass.ep.nonce2) : null
				},
				status: sharedPass.status
			};
			const { result: decrypted, error } = decrypt_shared(sharedPassObj, client);
			if (error) {
				console.error(error);
				return null;
			}
			if (!decrypted) {
				return null;
			}
			return [decrypted, uuid3, uuid5, sharedPass.status];
		}
	);
	const sharedPasswords = await Promise.all(sharedPasswordsPromises);
	const sharedPasswords2: [Password[], Uuid[], Uuid[], ShareStatus[]] = sharedPasswords.filter(
		(p) => p !== null
	) as [Password[], Uuid[], Uuid[], ShareStatus[]];
	/*   if (!sharedResponse.ok) {
    return { 
      result: [passwords.map(p => p[0]), passwords.map(p => p[1])] as [Password[], Uuid[]], 
      shared: null, 
      error: sharedResponse.statusText 
    };
  } */

	// const sharedResult = await sharedResponse.json();
	console.log(sharedPasswords2);
	return {
		result: [passwords.map((p) => p[0]), passwords.map((p) => p[1])] as [Password[], Uuid[]],
		shared: [
			sharedPasswords2.map((p) => p[0]),
			sharedPasswords2.map((p) => p[1]),
			sharedPasswords2.map((p) => p[2]),
			sharedPasswords2.map((p) => p[3])
		] as [Password[], Uuid[], Uuid[], ShareStatus[]],
		error: null
	};
}

function decryptCredential(
	ep: EP,
	secretKey: Uint8Array | null,
	kyqKey: Uint8Array | null
): { result: Password | null; error: string | null } {
	if (!secretKey) {
		return { result: null, error: 'Missing client.secret' };
	}
	if (!ep.nonce2) {
		return { result: null, error: 'Missing nonce2' };
	}
	if (!kyqKey) {
		return { result: null, error: 'Missing client.ky_q' };
	}
	const hash = blake3(secretKey).slice(0, 32);
	const key = new Uint8Array(hash);
	const nonce2 =
		ep.nonce2 instanceof Uint8Array
			? ep.nonce2.slice(0, 24)
			: Uint8Array.from(ep.nonce2).slice(0, 24);
	const chacha = xchacha20poly1305(key, nonce2);
	const ciphertext1 =
		ep.ciphertext instanceof Uint8Array ? ep.ciphertext : Uint8Array.from(ep.ciphertext);
	const decryptedIntermediate = chacha.decrypt(ciphertext1);
	const nonce = ep.nonce instanceof Uint8Array ? ep.nonce : Uint8Array.from(ep.nonce);
	const hash2 = blake3(kyqKey!).slice(0, 32);
	const key2 = new Uint8Array(hash2);
	const cipher = xchacha20poly1305(key2, nonce);
	const finalDecrypted = cipher.decrypt(decryptedIntermediate);
	const decoded = decodePassword(finalDecrypted);
	return decoded ? { result: decoded, error: null } : { result: null, error: 'Decoding failed' };
}

function send(ep: EP, client: Client) {
	if (!client.secret) {
		return { result: null, error: 'Missing client.secret' };
	}
	const hash = blake3(client.secret).slice(0, 32);
	const nonce2 = randomBytes(24);
	const key = new Uint8Array(hash);
	const cipher = xchacha20poly1305(key, nonce2);
	const ciphertext = cipher.encrypt(ep.ciphertext);
	const ep2: EP = {
		ciphertext: ciphertext,
		nonce: ep.nonce,
		nonce2: nonce2
	};
	return { result: ep2, error: null };
}

function encrypt(pass: Password, client: Client) {
	const passb = encodePassword(pass);
	if (!passb) {
		return { result: null, error: "Échec de l'encodage du mot de passe" };
	}
	if (!client.ky_q) {
		return { result: null, error: 'Missing client.ky_q' };
	}
	const hash = blake3(client.ky_q).slice(0, 32);
	const nonce = randomBytes(24);
	const key = new Uint8Array(hash);
	const cipher = xchacha20poly1305(key, nonce);
	const ciphertext = cipher.encrypt(passb);
	const ep: EP = {
		ciphertext: ciphertext,
		nonce: nonce,
		nonce2: null
	};
	return { result: ep, error: null };
}

export async function update_pass(uuid: Uuid, uuid2: Uuid, pass: Password, client: Client) {
	try {
		const { result: ep, error } = encrypt(pass, client);
		if (!ep || error) {
			return { result: null, error: error || 'Encryption failed' };
		}
		const { result: eq, error: error2 } = send(ep, client);
		if (!eq || error2) {
			return { result: null, error: error || 'Send Encryption failed' };
		}

		const response = await fetch(
			API_URL + 'update_pass_json/' + uuidToStr(uuid) + '/' + uuidToStr(uuid2),
			{
				method: 'POST',
				headers: {
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({
					ciphertext: Array.from(eq.ciphertext),
					nonce: Array.from(eq.nonce),
					nonce2: eq.nonce2 ? Array.from(eq.nonce2) : null
				})
			}
		);

		if (!response.ok) {
			return { result: null, error: response.statusText };
		}

		// Invalider le cache pour forcer le rechargement des données
		invalidateCache(`get_all_${uuidToStr(uuid)}`);

		return { result: response, error: null };
	} catch (e) {
		return { result: null, error: e instanceof Error ? e.message : String(e) };
	}
}

export async function create_pass(uuid: Uuid, pass: Password, client: Client) {
	try {
		const { result: ep, error } = encrypt(pass, client);
		if (!ep || error) {
			return { result: null, error: error || 'Encryption failed' };
		}
		const { result: eq, error: error2 } = send(ep, client);
		if (!eq || error2) {
			return { result: null, error: error2 || 'Send Encryption failed' };
		}
		const response = await fetch(API_URL + 'create_pass_json/' + uuidToStr(uuid), {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json'
			},
			body: JSON.stringify({
				ciphertext: Array.from(eq.ciphertext),
				nonce: Array.from(eq.nonce),
				nonce2: eq.nonce2 ? Array.from(eq.nonce2) : null
			})
		});
		if (!response.ok) {
			return { result: null, error: response.statusText };
		}
		// Invalider le cache pour forcer le rechargement des données
		invalidateCache(`get_all_${uuidToStr(uuid)}`);

		return { result: response, error: null };
	} catch (e) {
		return { result: null, error: e instanceof Error ? e.message : String(e) };
	}
}
export async function delete_pass(uuid: Uuid, uuid2: Uuid, client: Client) {
	try {
		const response = await fetch(
			API_URL + 'delete_pass_json/' + uuidToStr(uuid) + '/' + uuidToStr(uuid2)
		);

		if (!response.ok) {
			return { result: null, error: response.statusText };
		}

		// Invalider le cache pour forcer le rechargement des données
		invalidateCache(`get_all_${uuidToStr(uuid)}`);

		return { result: response, error: null };
	} catch (e) {
		return { result: null, error: e instanceof Error ? e.message : String(e) };
	}
}

/**
 * Chiffre un mot de passe pour le partage avec un autre utilisateur
 * @param {Password} password - Le mot de passe à partager
 * @param {Uint8Array} recipientPublicKey - La clé publique du destinataire
 * @param {Client} client - Le client actuel
 * @returns {SharedPass|null} Le mot de passe partagé chiffré ou null en cas d'erreur
 */
function share_encrypt(
	password: Password,
	recipientPublicKey: Uint8Array,
	client: Client
): { result: SharedPass | null; error: string | null } {
	try {
		// Chiffrer le mot de passe avec la clé privée du client
		const { result: encryptedPass, error } = encrypt(password, client);
		if (!encryptedPass || error) {
			return { result: null, error: error || 'Échec du chiffrement du mot de passe' };
		}

		// Générer une clé partagée avec la clé publique du destinataire
		const { cipherText, sharedSecret } = ml_kem1024.encapsulate(recipientPublicKey);

		// Chiffrer le mot de passe avec la clé partagée
		const secretKey = blake3(sharedSecret).slice(0, 32);
		const nonce = randomBytes(24);
		const cipher = xchacha20poly1305(secretKey, nonce);

		// Convertir l'EP en tableau d'octets pour le chiffrement
		const epBytes = encodePassword(password);
		if (!epBytes) {
			return { result: null, error: "Échec de l'encodage du mot de passe" };
		}

		const ciphertextEP = cipher.encrypt(epBytes);

		// Créer l'EP chiffré
		const sharedEP: EP = {
			ciphertext: ciphertextEP,
			nonce: nonce,
			nonce2: null
		};

		// Créer le SharedPass
		const sharedPass: SharedPass = {
			kem_ct: cipherText,
			ep: sharedEP,
			status: ShareStatus.Pending
		};

		return { result: sharedPass, error: null };
	} catch (e) {
		return { result: null, error: e instanceof Error ? e.message : 'Erreur inconnue' };
	}
}

/**
 * Déchiffre un mot de passe partagé
 * @param {SharedPass} sharedPass - Le mot de passe partagé chiffré
 * @param {Client} client - Le client actuel
 * @returns {Password|null} Le mot de passe déchiffré ou null en cas d'erreur
 */
function decrypt_shared(
	sharedPass: SharedPass,
	client: Client
): { result: Password | null; error: string | null } {
	try {
		// Décapsuler la clé partagée avec la clé privée du client
		const sharedSecret = ml_kem1024.decapsulate(sharedPass.kem_ct, client.ky_q);

		// Dériver la clé de chiffrement
		const secretKey = blake3(sharedSecret).slice(0, 32);

		// Déchiffrer l'EP
		if (!sharedPass.ep.nonce) {
			return { result: null, error: "Nonce manquant dans l'EP partagé" };
		}

		const nonce =
			sharedPass.ep.nonce instanceof Uint8Array
				? sharedPass.ep.nonce
				: Uint8Array.from(sharedPass.ep.nonce);

		const cipher = xchacha20poly1305(secretKey, nonce);
		const ciphertext =
			sharedPass.ep.ciphertext instanceof Uint8Array
				? sharedPass.ep.ciphertext
				: Uint8Array.from(sharedPass.ep.ciphertext);

		const decryptedBytes = cipher.decrypt(ciphertext);

		// Décoder le mot de passe
		const password = decodePassword(decryptedBytes);
		if (!password) {
			return { result: null, error: 'Échec du décodage du mot de passe partagé' };
		}

		return { result: password, error: null };
	} catch (e) {
		return { result: null, error: e instanceof Error ? e.message : 'Erreur inconnue' };
	}
}

/**
 * Partage un mot de passe avec un autre utilisateur
 * @param {Uuid} ownerUuid - UUID du propriétaire du mot de passe
 * @param {Uuid} passUuid - UUID du mot de passe à partager
 * @param {Uuid} recipientUuid - UUID du destinataire
 * @param {Client} client - Le client actuel
 * @param {Uint8Array} recipientPublicKey - La clé publique du destinataire
 * @param {Password} password - Le mot de passe à partager
 * @returns {Promise<{result: string|null, error: string|null}>} Résultat de l'opération
 */
export async function share_pass(
	ownerUuid: Uuid,
	passUuid: Uuid,
	recipientEmail: string,
	client: Client,
	password: Password
): Promise<{ result: string | null; error: string | null }> {
	// Chiffrer le mot de passe pour le partage

	const recipientUuid = await get_uuid_from_email(recipientEmail);
	if (!recipientUuid) {
		return { result: null, error: "Échec de la récupération de l'UUID du destinataire" };
	}

	const recipientUuid2 = new Uuid(recipientUuid);
	const uuid: Uuid = {
		bytes: new Uint8Array(recipientUuid2.toBytes())
	};

	const recipientPublicKey = await get_public_key(uuid);
	if (!recipientPublicKey) {
		return { result: null, error: 'Échec de la récupération de la clé publique du destinataire' };
	}

	const { result: sharedPass, error } = share_encrypt(password, recipientPublicKey, client);
	if (!sharedPass || error) {
		return { result: null, error: error || 'Échec du chiffrement pour le partage' };
	}

	// Convertir le SharedPass en format JSON pour l'API
	const sharedPassJson = {
		kem_ct: Array.from(sharedPass.kem_ct),
		ep: {
			ciphertext: Array.from(sharedPass.ep.ciphertext),
			nonce: Array.from(sharedPass.ep.nonce),
			nonce2: sharedPass.ep.nonce2 ? Array.from(sharedPass.ep.nonce2) : null
		},
		status: sharedPass.status
	};

	// Envoyer la requête au serveur
	const res = await fetch(
		API_URL +
			'share_pass_json/' +
			uuidToStr(ownerUuid) +
			'/' +
			uuidToStr(passUuid) +
			'/' +
			uuidToStr(uuid),
		{
			method: 'POST',
			headers: {
				'Content-Type': 'application/json'
			},
			body: JSON.stringify(sharedPassJson)
		}
	);

	if (!res.ok) {
		return { result: null, error: res.statusText };
	}

	const result = await res.json();
	return { result, error: null };
}

/**
 * Annule le partage d'un mot de passe
 * @param {Uuid} ownerUuid - UUID du propriétaire du mot de passe
 * @param {Uuid} passUuid - UUID du mot de passe
 * @param {Uuid} recipientUuid - UUID du destinataire
 * @returns {Promise<{result: string|null, error: string|null}>} Résultat de l'opération
 */
export async function unshare_pass(
	ownerUuid: Uuid,
	passUuid: Uuid,
	recipientUuid: Uuid
): Promise<{ result: string | null; error: string | null }> {
	const res = await fetch(
		API_URL +
			'unshare_pass_json/' +
			uuidToStr(ownerUuid) +
			'/' +
			uuidToStr(passUuid) +
			'/' +
			uuidToStr(recipientUuid),
		{
			method: 'POST',
			headers: {
				'Content-Type': 'application/json'
			}
		}
	);

	if (!res.ok) {
		return { result: null, error: res.statusText };
	}

	const result = await res.json();
	return { result, error: null };
}

/**
 * Récupère un mot de passe partagé
 * @param {Uuid} recipientUuid - UUID du destinataire
 * @param {Uuid} ownerUuid - UUID du propriétaire du mot de passe
 * @param {Uuid} passUuid - UUID du mot de passe
 * @param {Client} client - Le client actuel
 * @returns {Promise<{result: Password|null, error: string|null}>} Le mot de passe déchiffré ou une erreur
 */
export async function get_shared_pass(
	recipientUuid: Uuid,
	ownerUuid: Uuid,
	passUuid: Uuid,
	client: Client
): Promise<{ result: Password | null; error: string | null }> {
	const res = await fetch(
		API_URL +
			'get_shared_pass_json/' +
			uuidToStr(recipientUuid) +
			'/' +
			uuidToStr(ownerUuid) +
			'/' +
			uuidToStr(passUuid)
	);

	if (!res.ok) {
		return { result: null, error: res.statusText };
	}

	const sharedPassJson = await res.json();

	// Convertir le JSON en SharedPass
	const sharedPass: SharedPass = {
		kem_ct: Uint8Array.from(sharedPassJson.kem_ct),
		ep: {
			ciphertext: Uint8Array.from(sharedPassJson.ep.ciphertext),
			nonce: Uint8Array.from(sharedPassJson.ep.nonce),
			nonce2: sharedPassJson.ep.nonce2 ? Uint8Array.from(sharedPassJson.ep.nonce2) : null
		},
		status: sharedPassJson.status
	};

	// Déchiffrer le mot de passe partagé
	const { result, error } = decrypt_shared(sharedPass, client);
	return { result, error };
}

export async function get_uuid_from_email(email: string) {
	const cacheKey = `uuid_from_email_${email}`;
	const response = await cachedFetch(API_URL + 'get_uuid_from_email/' + email, undefined, cacheKey);
	return response.ok ? await response.text() : null;
}

export async function get_public_key(uuid: Uuid) {
	const cacheKey = `public_key_${uuidToStr(uuid)}`;
	const response = await cachedFetch(
		API_URL + 'get_public_key/' + uuidToStr(uuid),
		undefined,
		cacheKey
	);
	return response.ok ? new Uint8Array(await response.json()) : null;
}

export async function get_shared_by_user(ownerUuid: Uuid): Promise<SharedByUser[] | null> {
	const res = await fetch(API_URL + 'get_shared_by_user/' + uuidToStr(ownerUuid));
	if (!res.ok) {
		return null;
	}
	const result = await res.json();
	return result;
}

export async function get_uuids_from_emails(emails: string[]): Promise<Uuid[] | null> {
	const res = await fetch(API_URL + 'get_uuids_from_emails/', {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json'
		},
		body: JSON.stringify(emails)
	});
	if (!res.ok) {
		return null;
	}
	const result = await res.json();
	return result;
}

export async function get_emails_from_uuids(uuids: Uuid[]): Promise<string[] | null> {
	const uuids2 = uuids.map((uuid) => {
		const uuid2 = uuidToStr(uuid);
		return uuid2;
	});
	const res = await fetch(API_URL + 'get_emails_from_uuids/', {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json'
		},
		body: JSON.stringify(uuids2)
	});
	if (!res.ok) {
		return null;
	}
	const result = await res.json();
	return result;
}
export async function get_shared_by_user_emails(
	ownerUuid: Uuid
): Promise<SharedByUserEmail[] | null> {
	const sharedByUser = await get_shared_by_user(ownerUuid);
	if (!sharedByUser) {
		return null;
	}
	const emails2 = await Promise.all(
		sharedByUser.map(async (user) => {
			const uuids = user.recipient_ids;
			const uuids2 = uuids.map((uuid) => {
				const uuid3 = new Uuid(uuid as unknown as string);
				const uuid4 = {
					bytes: new Uint8Array(uuid3.toBytes())
				};
				return uuid4;
			});
			const emails2 = await get_emails_from_uuids(uuids2);
			if (typeof user.pass_id == 'string') {
				const pass_id = new Uuid(user.pass_id);
				const pass_id2: Uuid = {
					bytes: new Uint8Array(pass_id.toBytes())
				};

				// Récupérer les statuts pour chaque partage
				const statuses = await Promise.all(
					uuids2.map(async (recipientUuid) => {
						try {
							const res = await fetch(
								API_URL +
									'get_shared_pass_status_json/' +
									uuidToStr(ownerUuid) +
									'/' +
									uuidToStr(pass_id2) +
									'/' +
									uuidToStr(recipientUuid)
							);

							if (!res.ok) {
								return ShareStatus.Pending; // Par défaut
							}

							const status = await res.json();
							return status as ShareStatus;
						} catch (error) {
							console.error('Erreur lors de la récupération du statut:', error);
							return ShareStatus.Pending; // Par défaut en cas d'erreur
						}
					})
				);

				if (emails2) {
					return {
						pass_id: pass_id2,
						emails: emails2,
						statuses: statuses
					};
				}
			}
		})
	);
	const emails3 = emails2.filter((email) => email !== undefined);
	return emails3;
}

export async function reject_shared_pass(recipientUuid: Uuid, ownerUuid: Uuid, passUuid: Uuid) {
	const res = await fetch(
		API_URL +
			'reject_shared_pass_json/' +
			uuidToStr(recipientUuid) +
			'/' +
			uuidToStr(ownerUuid) +
			'/' +
			uuidToStr(passUuid)
	);
	if (!res.ok) {
		return { result: null, error: res.statusText };
	}
	return { result: null, error: null };
}

export async function accept_shared_pass(recipientUuid: Uuid, ownerUuid: Uuid, passUuid: Uuid) {
	const res = await fetch(
		API_URL +
			'accept_shared_pass_json/' +
			uuidToStr(recipientUuid) +
			'/' +
			uuidToStr(ownerUuid) +
			'/' +
			uuidToStr(passUuid)
	);
	if (!res.ok) {
		return { result: null, error: res.statusText };
	}
	return { result: null, error: null };
}

// Fonction pour charger les emails par lots
export async function batchGetEmailsFromUuids(uuids: Uuid[]): Promise<string[]> {
	// Diviser en lots de 10 pour éviter les requêtes trop lourdes
	const batchSize = 10;
	const batches = [];

	for (let i = 0; i < uuids.length; i += batchSize) {
		batches.push(uuids.slice(i, i + batchSize));
	}

	// Exécuter les requêtes par lots
	const results = await Promise.all(
		batches.map(async (batch) => {
			const emails = await get_emails_from_uuids(batch);
			return emails ? emails.filter((email): email is string => email !== null) : [];
		})
	);

	// Fusionner les résultats
	return results.flat();
}

// Fonction pour charger les UUIDs par lots
export async function batchGetUuidsFromEmails(emails: string[]): Promise<Uuid[]> {
	// Diviser en lots de 10 pour éviter les requêtes trop lourdes
	const batchSize = 10;
	const batches = [];

	for (let i = 0; i < emails.length; i += batchSize) {
		batches.push(emails.slice(i, i + batchSize));
	}

	// Exécuter les requêtes par lots
	const results = await Promise.all(
		batches.map(async (batch) => {
			const uuids = await get_uuids_from_emails(batch);
			return uuids ? uuids.filter((uuid): uuid is Uuid => uuid !== null) : [];
		})
	);

	// Fusionner les résultats
	return results.flat();
}

/**
 * Exporte les mots de passe au format JSON
 * @param {Uuid} userUuid - UUID de l'utilisateur
 * @param {Password[]} passwords - Liste des mots de passe à exporter
 * @param {Uuid[]} passwordUuids - Liste des UUIDs des mots de passe à exporter
 * @returns {string} Chaîne JSON contenant les mots de passe exportés
 */
export function exportPasswords(
	userUuid: Uuid,
	passwords: Password[],
	passwordUuids: Uuid[]
): string {
	// Créer une structure de données pour l'export
	const exportData = {
		version: 1,
		timestamp: new Date().toISOString(),
		userId: uuidToStr(userUuid),
		passwords: passwords.map((password, index) => {
			return {
				id: uuidToStr(passwordUuids[index]),
				username: password.username,
				password: password.password,
				app_id: password.app_id || null,
				description: password.description || null,
				url: password.url || null,
				otp: password.otp || null
			};
		})
	};

	// Convertir en JSON pour l'export
	return JSON.stringify(exportData, null, 2);
}

/**
 * Génère un fichier CSV contenant les mots de passe
 * @param {Uuid} userUuid - UUID de l'utilisateur
 * @param {Password[]} passwords - Liste des mots de passe à exporter
 * @returns {string} Contenu CSV des mots de passe
 */
export function exportPasswordsCSV(userUuid: Uuid, passwords: Password[]): string {
	// En-tête CSV
	let csv = "Nom d'utilisateur,Mot de passe,Application,Description,URL,Code OTP\r\n";

	// Ajouter chaque mot de passe au CSV
	passwords.forEach((password) => {
		// Échapper les virgules et guillemets dans les champs
		const username = `"${(password.username || '').replace(/"/g, '""')}"`;
		const pass = `"${(password.password || '').replace(/"/g, '""')}"`;
		const appId = password.app_id ? `"${password.app_id.replace(/"/g, '""')}"` : '""';
		const description = password.description
			? `"${password.description.replace(/"/g, '""')}"`
			: '""';
		const url = password.url ? `"${password.url.replace(/"/g, '""')}"` : '""';
		const otp = password.otp ? `"${password.otp.replace(/"/g, '""')}"` : '""';

		csv += `${username},${pass},${appId},${description},${url},${otp}\r\n`;
	});

	return csv;
}

/**
 * Génère un fichier texte simple contenant les mots de passe
 * @param {Uuid} userUuid - UUID de l'utilisateur
 * @param {Password[]} passwords - Liste des mots de passe à exporter
 * @returns {string} Contenu texte des mots de passe
 */
export function exportPasswordsText(userUuid: Uuid, passwords: Password[]): string {
	// Génération du fichier texte avec formatage
	let text = `EXPORT DE MOTS DE PASSE\n`;
	text += `Date: ${new Date().toLocaleString()}\n`;
	text += `Utilisateur: ${uuidToStr(userUuid)}\n\n`;

	// Ajouter chaque mot de passe
	passwords.forEach((password, index) => {
		text += `=== ${index + 1}. ${password.app_id || 'Sans nom'} ===\n`;
		text += `Nom d'utilisateur: ${password.username || ''}\n`;
		text += `Mot de passe: ${password.password || ''}\n`;

		if (password.url) {
			text += `URL: ${password.url}\n`;
		}

		if (password.otp) {
			text += `OTP: ${password.otp}\n`;
		}

		if (password.description) {
			text += `Description: ${password.description}\n`;
		}

		text += `\n`;
	});

	return text;
}
