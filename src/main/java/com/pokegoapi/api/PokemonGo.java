/*
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.pokegoapi.api;

import POGOProtos.Data.Player.CurrencyOuterClass;
import POGOProtos.Data.Player.PlayerStatsOuterClass;
import POGOProtos.Data.PlayerDataOuterClass;
import POGOProtos.Enums.PokemonFamilyIdOuterClass.PokemonFamilyId;
import POGOProtos.Enums.PokemonIdOuterClass;
import POGOProtos.Inventory.InventoryItemOuterClass;
import POGOProtos.Inventory.ItemIdOuterClass;
import POGOProtos.Networking.Envelopes.RequestEnvelopeOuterClass;
import POGOProtos.Networking.Requests.Messages.GetInventoryMessageOuterClass.GetInventoryMessage;
import POGOProtos.Networking.Requests.Messages.GetPlayerMessageOuterClass.GetPlayerMessage;
import POGOProtos.Networking.Requests.RequestTypeOuterClass.RequestType;
import POGOProtos.Networking.Responses.GetInventoryResponseOuterClass.GetInventoryResponse;
import POGOProtos.Networking.Responses.GetPlayerResponseOuterClass.GetPlayerResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import com.pokegoapi.api.inventory.Bag;
import com.pokegoapi.api.inventory.CandyJar;
import com.pokegoapi.api.inventory.Item;
import com.pokegoapi.api.inventory.PokeBank;
import com.pokegoapi.api.map.Map;
import com.pokegoapi.api.player.ContactSettings;
import com.pokegoapi.api.player.DailyBonus;
import com.pokegoapi.api.player.PlayerAvatar;
import com.pokegoapi.api.player.PlayerProfile;
import com.pokegoapi.api.player.Team;
import com.pokegoapi.api.pokemon.Pokemon;
import com.pokegoapi.exceptions.InvalidCurrencyException;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;
import com.pokegoapi.main.RequestHandler;
import com.pokegoapi.main.ServerRequest;
import com.pokegoapi.util.Log;
import lombok.Getter;
import lombok.Setter;
import okhttp3.OkHttpClient;


public class PokemonGo {

	private static final java.lang.String TAG = PokemonGo.class.getSimpleName();
	@Getter
	RequestHandler requestHandler;
	@Getter
	PokeBank pokebank;
	@Getter
	Bag bag;
	@Getter
	Map map;
	@Getter
	CandyJar candyjar;
	private PlayerProfile playerProfile;
	@Getter
	@Setter
	private double latitude;
	@Getter
	@Setter
	private double longitude;
	@Getter
	@Setter
	private double altitude;

	private long lastInventoryUpdate;

	/**
	 * Instantiates a new Pokemon go.
	 *
	 * @param auth   the auth
	 * @param client the client
	 */
	public PokemonGo(RequestEnvelopeOuterClass.RequestEnvelope.AuthInfo auth, OkHttpClient client) {
		playerProfile = null;

		// send profile request to get the ball rolling
		requestHandler = new RequestHandler(this, auth, client);
		getPlayerProfile();
		// should have proper end point now.

		map = new Map(this);
		lastInventoryUpdate = 0;
	}

	private PlayerDataOuterClass.PlayerData getPlayerAndUpdateInventory(PlayerProfile playerProfile)
			throws LoginFailedException, RemoteServerException {

		GetPlayerMessage getPlayerReqMsg = GetPlayerMessage.newBuilder().build();
		ServerRequest getPlayerServerRequest = new ServerRequest(RequestType.GET_PLAYER, getPlayerReqMsg);

		GetInventoryMessage invReqMsg = GetInventoryMessage.newBuilder()
				.setLastTimestampMs(this.lastInventoryUpdate)
				.build();
		ServerRequest getInventoryServerRequest = new ServerRequest(RequestType.GET_INVENTORY, invReqMsg);

		getRequestHandler().sendServerRequests(getPlayerServerRequest, getInventoryServerRequest);

		GetPlayerResponse getPlayerResponse;
		GetInventoryResponse getInventoryResponse;
		try {
			getPlayerResponse = GetPlayerResponse.parseFrom(getPlayerServerRequest.getData());
			getInventoryResponse = GetInventoryResponse.parseFrom(getInventoryServerRequest.getData());
		} catch (InvalidProtocolBufferException e) {
			throw new RemoteServerException(e);
		}

		pokebank = new PokeBank(this);
		bag = new Bag(this);
		candyjar = new CandyJar(this);

		for (InventoryItemOuterClass.InventoryItem item :
				getInventoryResponse.getInventoryDelta().getInventoryItemsList()) {

			if (item.getInventoryItemData().getPokemonData().getPokemonId() != PokemonIdOuterClass.PokemonId.MISSINGNO) {
				pokebank.addPokemon(new Pokemon(item.getInventoryItemData().getPokemonData()));
			}

			if (item.getInventoryItemData().getItem().getItemId() != ItemIdOuterClass.ItemId.ITEM_UNKNOWN) {
				bag.addItem(new Item(item.getInventoryItemData().getItem()));
			}

			if (item.getInventoryItemData().getPokemonFamily().getFamilyId() != PokemonFamilyId.UNRECOGNIZED) {
				candyjar.setCandy(
						item.getInventoryItemData().getPokemonFamily().getFamilyId(),
						item.getInventoryItemData().getPokemonFamily().getCandy());
			}

			if (item.getInventoryItemData().hasPlayerStats()) {
				PlayerStatsOuterClass.PlayerStats stats = item.getInventoryItemData().getPlayerStats();
				playerProfile.setStats(stats);
			}

		}

		return getPlayerResponse.getPlayerData();
	}

	/**
	 * Gets player profile.
	 *
	 * @return the player profile
	 */
	public PlayerProfile getPlayerProfile() {
		return getPlayerProfile(false);
	}

	/**
	 * Gets player profile.
	 *
	 * @param forceUpdate the force update
	 * @return the player profile
	 */
	public PlayerProfile getPlayerProfile(boolean forceUpdate) {
		if (!forceUpdate && playerProfile != null) {
			return playerProfile;
		}

		// init here so we can set the experience and level, which is somehow contained in the inventory...
		PlayerProfile tempProfile = new PlayerProfile();
		PlayerDataOuterClass.PlayerData localPlayer = null;
		try {
			localPlayer = getPlayerAndUpdateInventory(tempProfile);
		} catch (LoginFailedException | RemoteServerException e) {
			Log.e(TAG, "Failed to get profile data and update inventory", e);
		}

		if (localPlayer == null) {
			return null;
		}
		playerProfile = tempProfile;

		playerProfile.setBadge(localPlayer.getEquippedBadge());
		playerProfile.setCreationTime(localPlayer.getCreationTimestampMs());
		playerProfile.setItemStorage(localPlayer.getMaxItemStorage());
		playerProfile.setPokemonStorage(localPlayer.getMaxPokemonStorage());
		playerProfile.setTeam(Team.values()[localPlayer.getTeam()]);
		playerProfile.setUsername(localPlayer.getUsername());

		final PlayerAvatar avatarApi = new PlayerAvatar();
		final DailyBonus bonusApi = new DailyBonus();
		final ContactSettings contactApi = new ContactSettings();

		// maybe something more graceful?
		for (CurrencyOuterClass.Currency currency : localPlayer.getCurrenciesList()) {
			try {
				playerProfile.addCurrency(currency.getName(), currency.getAmount());
			} catch (InvalidCurrencyException e) {
				Log.w(TAG, "Error adding currency. You can probably ignore this.", e);
			}
		}

		avatarApi.setGender(localPlayer.getAvatar().getGender());
		avatarApi.setBackpack(localPlayer.getAvatar().getBackpack());
		avatarApi.setEyes(localPlayer.getAvatar().getEyes());
		avatarApi.setHair(localPlayer.getAvatar().getHair());
		avatarApi.setHat(localPlayer.getAvatar().getHat());
		avatarApi.setPants(localPlayer.getAvatar().getPants());
		avatarApi.setShirt(localPlayer.getAvatar().getShirt());
		avatarApi.setShoes(localPlayer.getAvatar().getShoes());
		avatarApi.setSkin(localPlayer.getAvatar().getSkin());

		bonusApi.setNextCollectionTimestamp(localPlayer.getDailyBonus().getNextCollectedTimestampMs());
		bonusApi.setNextDefenderBonusCollectTimestamp(localPlayer.getDailyBonus().getNextDefenderBonusCollectTimestampMs());

		playerProfile.setAvatar(avatarApi);
		playerProfile.setDailyBonus(bonusApi);

		return playerProfile;
	}

	/**
	 * Sets location.
	 *
	 * @param latitude  the latitude
	 * @param longitude the longitude
	 * @param altitude  the altitude
	 */
	public void setLocation(double latitude, double longitude, double altitude) {
		setLatitude(latitude);
		setLongitude(longitude);
		setAltitude(altitude);
	}
}
