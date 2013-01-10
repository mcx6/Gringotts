package org.gestern.gringotts;

import static org.gestern.gringotts.Permissions.*;
import static org.gestern.gringotts.api.TransactionResult.*;

import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.gestern.gringotts.accountholder.AccountHolder;
import org.gestern.gringotts.accountholder.PlayerAccountHolder;
import org.gestern.gringotts.api.TransactionResult;


public class GringottsAccount {

    @SuppressWarnings("unused")
	private final Logger log = Gringotts.G.getLogger();
    private final DAO dao = DAO.getDao(); 

    public final AccountHolder owner;
    
	private final Configuration config = Configuration.config;

    public GringottsAccount(AccountHolder owner) {
    	if (owner == null) {
    		throw new IllegalArgumentException("owner parameter to Account constructor may not be null");
    	}
        this.owner = owner;
    }

    /**
     * Current balance of this account in cents
     * @return current balance of this account in cents
     */
    public long balance() {
        long balance = 0;
        
        if (config.usevaultContainer) {
	        for (AccountChest chest : dao.getChests(this))
	            balance += chest.balance();
        }
        
        Player player = playerOwner();
        if (player != null) {
        	if (usevault_inventory.allowed(player))
        		balance += new AccountInventory(player.getInventory()).balance();
        	if (config.usevaultEnderchest && usevault_enderchest.allowed(player))
        		balance += new AccountInventory(player.getEnderChest()).balance();
        }

        // convert to total cents
        return balance + dao.getCents(this);
    }

    /**
     * Add an amount in cents to this account if able to.
     * @param amount
     * @return Whether amount successfully added
     */
    public TransactionResult add(long amount) {

        // Cannot add negative amount
        if(amount < 0)
            return ERROR;

        long centsStored = dao.getCents(this);
        long remaining = amount + centsStored;

        // add currency to account's vaults
        if (config.usevaultContainer) {
	        for (AccountChest chest : dao.getChests(this)) {
	        	remaining -= chest.add(remaining);
	            if (remaining <= 0) break;
	        }
        }
        
        // add stuff to player's inventory and enderchest too, when they are online
        Player player = playerOwner();
        if (player != null) {
        	if (usevault_inventory.allowed(player))
        		remaining -= new AccountInventory(player.getInventory()).add(remaining);
        	if (Configuration.config.usevaultEnderchest && usevault_enderchest.allowed(player))
        		remaining -= new AccountInventory(player.getEnderChest()).add(remaining);
        }
        
        // allow largest denom value as threshold for available space
    	// TODO make maximum virtual amount configurable
    	long largestDenomValue = config.currency.denominations().get(0).value;
    	if (remaining < largestDenomValue) {
    		dao.storeCents(this, remaining);
    		remaining = 0;
    	}
        
        if (remaining == 0) 
        	return SUCCESS;
        
        // failed, remove the stuff added so far
        remove(amount-remaining);
        
        return INSUFFICIENT_SPACE;
    }

    /**
     * Attempt to remove an amount in cents from this account. 
     * If the account contains less than the specified amount, returns false
     * @param amount
     * @return amount actually removed.
     */
    public TransactionResult remove(long amount) {

        // Cannot remove negative amount
        if(amount < 0)
            return ERROR;

        // Make sure we have enough to remove
        if(balance() < amount)
            return INSUFFICIENT_FUNDS;

        long remaining = amount;

        // Now remove the physical amount left
        if (config.usevaultContainer) {
	        for (AccountChest chest : dao.getChests(this))
	            remaining -= chest.remove(remaining);
        }
        
        Player player = playerOwner();
        if (player != null) {
        	if (usevault_inventory.allowed(player))
        		remaining -= new AccountInventory(player.getInventory()).remove(remaining);
        	if (config.usevaultEnderchest && usevault_enderchest.allowed(player))
        		remaining -= new AccountInventory(player.getEnderChest()).remove(remaining);
        }
        
        if (remaining < 0)
        	// took too much, pay back the extra
        	return add(-remaining);
        
        if (remaining > 0) {
        	// cannot represent the leftover in our denominations, take them from the virtual reserve
        	long cents = dao.getCents(this);
        	dao.storeCents(this, cents - remaining);
        }

        return SUCCESS;
    }


    /**
     * Attempt to transfer an amount of currency to another account. 
     * If the transfer fails because of insufficient funds, both accounts remain at previous
     * balance, and false is returned.
     * @param value amount to transfer
     * @param other account to transfer funds to.
     * @return false if this account had insufficient funds.
     */
    public TransactionResult transfer(long value, GringottsAccount other) {

        // First try to deduct the amount from this account
    	TransactionResult removed = this.remove(value);
        if(removed == SUCCESS) {
            // Okay, now lets send it to the other account
        	TransactionResult added = other.add(value);
            if(added!=SUCCESS) {
                // Oops, failed, better refund this account
                this.add(value);
            }
            return added;
        } 
        return removed;
    }
    
    @Override
    public String toString() {
    	return "Account ("+owner+")";
    }
    
    /**
     * Returns the player owning this account, if the owner is actually a player and online.
     * @return the player owning this account, if the owner is actually a player and online, otherwise null
     */
    private Player playerOwner() {
    	if (owner instanceof PlayerAccountHolder) {
        	OfflinePlayer player = ((PlayerAccountHolder) owner).accountHolder;
        	return player.getPlayer();
        }
    	
    	return null;
    }
    
    


}